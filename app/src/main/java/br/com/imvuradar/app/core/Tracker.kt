package br.com.imvuradar.app.core

import br.com.imvuradar.app.App
import br.com.imvuradar.app.Config
import br.com.imvuradar.app.Notifications
import br.com.imvuradar.app.SecureStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Orquestrador enxuto:
 *  - ONDE: localiza o nick nas salas publicas/listadas (REST)
 *  - PRIVADA: rastreio via IMQ (sala atual, nome, privacidade e roster)
 *
 * Sem scanner global, sem vigia, sem caca por sala, sem forcar entrada.
 */
object Tracker {

    val api = ImvuApi()
    val imq = ImqClient { evt ->
        if (evt.optString("type") == "presence") handlePresence(evt)
        emit(evt)
    }
    val index: RoomIndex by lazy { RoomIndex(App.ctx) }

    private val pool = Executors.newCachedThreadPool()
    private val probeGen = AtomicInteger(0)
    private val store: SecureStore by lazy { SecureStore(App.ctx) }

    @Volatile var listener: ((JSONObject) -> Unit)? = null
    @Volatile var started = false
    @Volatile var lastEventAt = 0L
    @Volatile var reconnecting = false

    private var credentials: Pair<String, String>? = null

    @Volatile private var botUser: String = Config.DEFAULT_USER

    @Volatile private var targetCid: String? = null
    @Volatile private var targetNick: String = ""
    @Volatile private var targetProfile: JSONObject? = null
    @Volatile private var currentRoom: String? = null
    @Volatile private var currentName: String = ""
    @Volatile private var currentPrivacy: String = ""
    @Volatile private var currentDetectedBy: String = ""
    @Volatile private var settingRoom: String? = null
    @Volatile private var lastWhere: JSONObject? = null
    private var roomSeq = 0

    private val roster = LinkedHashMap<String, String>()
    private val rosterLock = Any()

    fun emit(evt: JSONObject) {
        lastEventAt = System.currentTimeMillis()
        try { listener?.invoke(evt) } catch (_: Exception) { }
    }

    private fun personJson(p: Person?): JSONObject {
        if (p == null) return JSONObject()
        return JSONObject()
            .put("cid", p.cid)
            .put("username", p.username)
            .put("display_name", p.displayName)
            .put("avatar", p.avatar)
            .put("render", p.render)
            .put("is_vip", p.isVip)
            .put("is_ap", p.isAp)
            .put("online", p.online)
    }

    private fun peopleJson(list: List<Person>): JSONArray {
        val arr = JSONArray()
        list.forEach { arr.put(personJson(it)) }
        return arr
    }

    // ---------------- ciclo de vida ----------------
    fun start() {
        if (started) return
        started = true
        api.onRoster = { rid, cids -> index.updateRoom(rid, cids) }
        credentials = store.credentials()
        pool.submit {
            var tries = 0
            while (true) {
                var cr = credentials
                if (cr == null) cr = store.credentials()
                val use = cr ?: Pair(Config.DEFAULT_USER, Config.DEFAULT_PASS)
                credentials = use
                if (api.login(use.first, use.second)) {
                    reconnecting = false
                    botUser = use.first
                    emit(JSONObject().put("type", "ready").put("imq", true))
                    imq.connect(api.cid, api.sessionId, use.first)
                    targetCid?.let { imq.setTarget(it) }
                    return@submit
                }
                reconnecting = true
                emit(JSONObject().put("type", "login_error").put("message", api.lastError))
                tries++
                Thread.sleep((if (tries > 5) 30_000 else 8_000).toLong())
            }
        }
        pool.submit {
            while (true) {
                Thread.sleep(15_000)
                try { imq.ping() } catch (_: Exception) { }
            }
        }
    }

    fun setCredentials(user: String, pass: String) {
        store.saveCredentials(user, pass)
        credentials = Pair(user, pass)
        pool.submit {
            if (api.login(user, pass)) {
                botUser = user
                emit(JSONObject().put("type", "ready").put("imq", true))
                imq.connect(api.cid, api.sessionId, user)
                targetCid?.let { imq.setTarget(it) }
            } else {
                emit(JSONObject().put("type", "login_error").put("message", api.lastError))
            }
        }
    }

    fun hasCredentials(): Boolean = store.hasCredentials()

    // ---------------- ONDE (salas publicas/listadas) ----------------
    fun where(nick: String) {
        pool.submit {
            lastWhere = null
            val p = api.resolve(nick)
            if (p == null) {
                emit(JSONObject().put("type", "where").put("nick", nick).put("error", "nick nao encontrado"))
                return@submit
            }
            val hits = mutableListOf<Pair<String, List<Person>>>()
            val seen = mutableSetOf<String>()

            // indice local (se ja viu esse cid em algum roster)
            for (rid in index.roomsOf(p.cid)) {
                if (!seen.add(rid)) continue
                val people = api.roster(rid)
                if (people.any { it.cid == p.cid }) hits.add(Pair(rid, people))
            }
            if (hits.isNotEmpty()) emitWhere(p, hits.toList())

            // listas oficiais do IMVU
            api.findAllRoomsFor(
                p.cid,
                listOf("friends", "recent", "explore"),
                onHit = { rid, people ->
                    if (seen.add(rid)) {
                        hits.add(Pair(rid, people))
                        imq.subscribeRoom(rid)
                        emitWhere(p, hits.toList())
                    }
                },
                deadlineMs = System.currentTimeMillis() + 25_000
            )

            // salas proprias do alvo (publicas/listadas)
            val own = api.findAllInOwnRooms(p.cid, 120)
            for (h in own) {
                if (seen.add(h.first)) {
                    hits.add(h)
                    imq.subscribeRoom(h.first)
                }
            }
            emitWhere(p, hits.toList())
        }
    }

    private fun emitWhere(p: Person, hits: List<Pair<String, List<Person>>>) {
        val rooms = JSONArray()
        var first: JSONObject? = null
        for (h in hits) {
            val meta = api.roomJson(h.first)
            meta.put("people", h.second.size)
            meta.put("peopleList", peopleJson(h.second))
            meta.put("source", "listas do imvu")
            rooms.put(meta)
            if (first == null) first = meta
        }
        val data = JSONObject()
            .put("profile", personJson(p))
            .put("rooms", rooms)
            .put("room", first ?: JSONObject())
            .put("people", peopleJson(hits.firstOrNull()?.second ?: emptyList()))
        lastWhere = data
        emit(JSONObject().put("type", "where").put("nick", p.username).put("data", data))
    }

    // ---------------- PRIVADA (IMQ) ----------------
    fun track(nick: String) {
        pool.submit {
            val p = api.resolve(nick)
            if (p == null) {
                emit(JSONObject().put("type", "track_error").put("message", "nick nao encontrado"))
                return@submit
            }

            // cancela qualquer probe antigo ANTES de trocar o alvo (mesmo se for o mesmo nick)
            val gen = probeGen.incrementAndGet()

            synchronized(this) {
                targetCid = p.cid
                targetNick = p.username
                targetProfile = personJson(p)
                currentRoom = null
                currentName = ""
                currentPrivacy = ""
                currentDetectedBy = ""
                settingRoom = null
                roomSeq++
                synchronized(rosterLock) { roster.clear() }
            }

            imq.setTarget(p.cid)
            emit(JSONObject().put("type", "track_start").put("target", personJson(p)))
            emit(JSONObject().put("type", "track_status").put("msg", "sync inicial (HTTP)..."))

            // SYNC INICIAL IMEDIATO: le o estado ATUAL por HTTP, sem esperar WebSocket
            val init = initialSync(p)
            if (init != null && probeGen.get() == gen && targetCid == p.cid) {
                setCurrentRoom(init.rid, init.people, init.via)
                emit(JSONObject().put("type", "track_status").put("msg", "sync ok - tempo real ativo"))
            } else {
                emit(JSONObject().put("type", "track_status").put("msg", "sem snapshot HTTP; aguardando evento do IMQ..."))
            }

            // probe roda na MESMA conexao IMQ e em thread propria: nunca segura o pool
            startProbeLoop(p.cid, gen)

            // publicas: thread paralela (aditiva, nao interfere no fluxo privado/IMQ)
            PublicWatch.start(p.cid, p.username) { evt -> onPublicHit(evt) }

            // fonte extra: salas privadas que o indice do catvu ja conhece
            pool.submit {
                val hint = try { api.catvuRooms(p.username) } catch (_: Exception) { emptyList() }
                if (hint.isNotEmpty() && probeGen.get() == gen &&
                    targetCid == p.cid && currentRoom == null
                ) {
                    setCurrentRoom(hint.first(), via = "catvu")
                }
            }

            // fallback HTTP rapido: listas oficiais; aplica o primeiro hit na hora
            val hits = mutableListOf<Pair<String, List<Person>>>()
            if (currentRoom == null) {
                try {
                    hits.addAll(
                        api.findAllRoomsFor(
                            p.cid,
                            listOf("friends", "recent", "explore"),
                            deadlineMs = System.currentTimeMillis() + 15_000,
                            stopOnFirst = true
                        )
                    )
                } catch (_: Exception) { }
                if (currentRoom == null && hits.isNotEmpty()) {
                    setCurrentRoom(hits.first().first, hits.first().second, "roster")
                }
            }

            // salas proprias do alvo: varredura lenta, so se ainda nao achou
            if (currentRoom == null) {
                try { hits.addAll(api.findAllInOwnRooms(p.cid, 120)) } catch (_: Exception) { }
                if (currentRoom == null && hits.isNotEmpty()) {
                    setCurrentRoom(hits.first().first, hits.first().second, "roster")
                }
            }

            for (h in hits) imq.subscribeRoom(h.first)
            if (currentRoom == null) {
                emit(JSONObject().put("type", "track_status").put("msg", "aguardando evento do IMQ..."))
            }
        }
    }

    private data class InitialRoom(val rid: String, val people: List<Person>, val via: String)

    /**
     * SYNC INICIAL imediato (sem sleep): pega a sala ATUAL do alvo por HTTP.
     *  1) alvo == conta do bot -> log local do WebView / recent_rooms (sessao dona)
     *  2) lista de amigos do IMVU -> sala atual do amigo (vem denormalizada)
     *  3) catvu -> indice externo de sala privada ocupada
     * Depois disso o IMQ assume para entrada/saida em tempo real.
     */
    private fun initialSync(p: Person): InitialRoom? {
        // 1) o alvo e a propria conta logada no bot
        if (p.cid == api.cid) {
            val local = LocalState.lastRoomId
            if (local.isNotEmpty()) {
                val people = try { api.roster(local) } catch (_: Exception) { emptyList() }
                if (people.any { it.cid == p.cid }) {
                    return InitialRoom(local, people, "local")
                }
            }
            try {
                val self = api.selfCurrentRoom()
                if (self != null) return InitialRoom(self.first, self.second, "self_recent")
            } catch (_: Exception) { }
        }

        // 2) lista de amigos: o IMVU mantem a sala atual de cada amigo
        try {
            val hints = api.friendsRoomHints()
            for (h in hints) {
                val people = try { api.roster(h.rid) } catch (_: Exception) { emptyList() }
                if (people.any { it.cid == p.cid }) {
                    return InitialRoom(h.rid, people, "friends")
                }
            }
            // sala privada: o roster pode vir 403, mas a lista ainda aponta a
            // sala. Com um unico amigo (o alvo), a sala da lista e a dele.
            if (hints.size == 1) {
                val friends = api.friendCids()
                if (friends.size == 1 && p.cid in friends) {
                    return InitialRoom(hints.first().rid, emptyList(), "friends")
                }
            }
        } catch (_: Exception) { }

        // 3) indice externo de sala privada
        try {
            val hint = api.catvuRooms(p.username)
            if (hint.isNotEmpty()) return InitialRoom(hint.first(), emptyList(), "catvu")
        } catch (_: Exception) { }

        return null
    }

    private fun startProbeLoop(target: String, gen: Int) {
        val t = Thread {
            var lastLive = emptySet<String>()
            var myRoomStreak = 0
            val restricted = HashMap<String, Boolean>()
            while (probeGen.get() == gen && targetCid == target) {
                val live = probeOnce(target)
                if (probeGen.get() != gen || targetCid != target) break
                if (live.isEmpty()) {
                    if (currentRoom == null) {
                        emit(JSONObject().put("type", "track_status")
                            .put("msg", "aguardando evento do IMQ..."))
                    }
                } else {
                    myRoomStreak = if ("$target-1" in live) myRoomStreak + 1 else 0
                    evaluateLive(target, live, lastLive, myRoomStreak, restricted)
                    lastLive = live
                }
                Thread.sleep(15_000)
            }
        }
        t.isDaemon = true
        t.name = "radar-probe"
        t.start()
    }

    private fun probeOnce(target: String): Set<String> {
        val latch = CountDownLatch(1)
        val ref = AtomicReference<List<String>>(emptyList())
        imq.probeQueues(target, 25_000) { live ->
            ref.set(live)
            latch.countDown()
        }
        latch.await(30, TimeUnit.SECONDS)
        return ref.get().toSet()
    }

    /**
     * Decide a sala do alvo sem roster privado (a API do IMVU da 403 pra terceiros):
     *  1) sala publica viva com o cid no roster -> certeza
     *  2) sala que passou a existir entre dois probes -> alguem entrou nela agora
     *  3) My Room (<cid>-1) viva em 2 probes seguidos -> heuristica forte
     *  4) uma unica sala privada viva -> certeza pratica
     * O evento edge:participants do IMQ continua sendo a fonte definitiva.
     */
    private fun evaluateLive(
        target: String,
        live: Set<String>,
        lastLive: Set<String>,
        myRoomStreak: Int,
        restricted: MutableMap<String, Boolean>
    ) {
        if (currentRoom != null) return
        val own = live.filter { it.startsWith("$target-") }
        if (own.isEmpty()) return

        // 1) roster publico diz a verdade
        if (lastLive.isEmpty()) {
            for (rid in own) {
                if (isRestricted(rid, restricted)) continue
                val people = try { api.roster(rid) } catch (_: Exception) { emptyList() }
                if (people.any { it.cid == target }) {
                    setCurrentRoom(rid, people, "roster")
                    return
                }
            }
        }

        // 2) fila que apareceu entre probes (1 -> 0) = sala recem-ocupada
        if (lastLive.isNotEmpty()) {
            val fresh = own - lastLive
            if (fresh.size == 1) {
                setCurrentRoom(fresh.first(), via = "imq_new")
                return
            }
        }

        // 3) My Room viva em dois probes seguidos
        if (myRoomStreak >= 2) {
            setCurrentRoom("$target-1", via = "myroom")
            return
        }

        // 4) unica sala privada viva
        val priv = own.filter { isRestricted(it, restricted) }
        if (priv.size == 1) {
            setCurrentRoom(priv.first(), via = "imq_probe")
        }
    }

    private fun isRestricted(rid: String, cache: MutableMap<String, Boolean>): Boolean {
        cache[rid]?.let { return it }
        val r = try { api.roomJson(rid).optBoolean("restricted", false) } catch (_: Exception) { false }
        cache[rid] = r
        return r
    }

    fun stopTrack() {
        probeGen.incrementAndGet()
        PublicWatch.stop()
        synchronized(this) {
            targetCid = null
            targetNick = ""
            targetProfile = null
            currentRoom = null
            currentName = ""
            currentPrivacy = ""
            currentDetectedBy = ""
            settingRoom = null
            roomSeq++
            synchronized(rosterLock) { roster.clear() }
        }
        imq.clearTarget()
        emit(JSONObject().put("type", "track_stopped"))
    }

    private fun handlePresence(evt: JSONObject) {
        val target = targetCid ?: return
        val room = evt.optString("room")
        val action = evt.optString("action")
        val cid = evt.optString("cid")
        if (room.isEmpty() || action.isEmpty() || cid.isEmpty()) return

        if (cid == target) {
            when (action) {
                "updated", "created", "joined" -> {
                    if (currentRoom != room) {
                        pool.submit { setCurrentRoom(room) }
                    }
                }
                "deleted", "left" -> {
                    if (currentRoom == room) {
                        val eraPrivada = currentPrivacy == "PRIVADA"
                        synchronized(this) {
                            roomSeq++
                            currentRoom = null
                            currentName = ""
                            currentPrivacy = ""
                            settingRoom = null
                            synchronized(rosterLock) { roster.clear() }
                        }
                        emit(JSONObject().put("type", "track_left").put("room", room))
                        if (eraPrivada) {
                            Notifications.alert(
                                App.ctx, "SAIU DA SALA",
                                "$targetNick saiu da sala de ID $room"
                            )
                        }
                    }
                }
            }
            return
        }

        if (currentRoom != room) return

        when (action) {
            "updated", "created", "joined" -> {
                val novo = synchronized(rosterLock) { cid !in roster }
                if (novo) {
                    // placeholder imediato evita duplicar evento enquanto busca o nick
                    synchronized(rosterLock) { roster[cid] = cid }
                    pool.submit {
                        val nick = api.usernameOf(cid)
                        val ok = synchronized(rosterLock) {
                            if (currentRoom == room && roster.containsKey(cid)) {
                                roster[cid] = nick
                                true
                            } else false
                        }
                        if (ok) {
                            emit(
                                JSONObject().put("type", "track_join")
                                    .put("room", room).put("cid", cid).put("nick", nick)
                            )
                            emitOccupants()
                        }
                    }
                }
            }
            "deleted", "left" -> {
                val nick = synchronized(rosterLock) { roster.remove(cid) }
                if (nick != null && nick != cid) {
                    emit(
                        JSONObject().put("type", "track_leave")
                            .put("room", room).put("cid", cid).put("nick", nick)
                    )
                    emitOccupants()
                } else if (nick != null) {
                    emitOccupants()
                }
            }
        }
    }

    /**
     * Hit do rastreio de publicas. O privado manda: se o IMQ ja apontou uma
     * sala privada, nao dispara alerta de publica (evita falso positivo).
     */
    private fun onPublicHit(evt: JSONObject) {
        emit(evt)
        if (currentPrivacy == "PRIVADA") return
        val room = evt.optJSONObject("room") ?: return
        val links = evt.optJSONObject("links") ?: JSONObject()
        val rid = room.optString("roomId")
        val name = room.optString("name").ifBlank { rid }
        val web = links.optString("web")
        val imvu = links.optString("imvu")
        val text = StringBuilder()
            .append(targetNick)
            .append(" esta na sala publica \"")
            .append(name)
            .append("\" (ID: ")
            .append(rid)
            .append(")")
        if (web.isNotEmpty()) text.append("\n").append(web)
        if (imvu.isNotEmpty()) text.append("\n").append(imvu)
        Notifications.alert(App.ctx, "SALA PUBLICA", text.toString())
        emit(
            JSONObject().put("type", "log")
                .put("msg", "[PUBLIC] $targetNick -> $name (ID: $rid)")
                .put("links", links)
        )
    }

    private fun setCurrentRoom(room: String, seed: List<Person>? = null, via: String = "imq") {
        synchronized(this) {
            if (currentRoom == room && currentName.isNotEmpty()) return
            if (settingRoom == room) return
            settingRoom = room
        }
        val seq = synchronized(this) {
            roomSeq++
            currentRoom = room
            currentName = ""
            currentPrivacy = ""
            currentDetectedBy = via
            synchronized(rosterLock) {
                roster.clear()
                targetCid?.let { roster[it] = targetNick }
            }
            roomSeq
        }

        val label = api.roomLabel(room)
        if (seq != roomSeq || currentRoom != room) {
            synchronized(this) { if (settingRoom == room) settingRoom = null }
            return
        }
        currentName = label.first
        currentPrivacy = label.second

        val people = seed ?: try {
            api.roster(room)
        } catch (_: Exception) {
            emptyList<Person>()
        }
        if (seq != roomSeq || currentRoom != room) {
            synchronized(this) { if (settingRoom == room) settingRoom = null }
            return
        }
        synchronized(rosterLock) {
            for (p in people) {
                if (p.cid == targetCid) continue
                roster[p.cid] = p.username
            }
        }
        imq.subscribeRoom(room)
        emitTrackRoom()
        emitOccupants()
        // notificacao no celular somente para sala privada (a publica fica na aba Onde)
        if (currentPrivacy == "PRIVADA") {
            Notifications.alert(
                App.ctx, "SALA PRIVADA",
                "$targetNick está na sala \"$currentName\" (ID: $room)"
            )
        }
        synchronized(this) { if (settingRoom == room) settingRoom = null }
    }

    private fun emitTrackRoom() {
        val room = currentRoom ?: return
        val occupants = JSONArray()
        synchronized(rosterLock) {
            roster.forEach { (cid, nick) ->
                occupants.put(JSONObject().put("cid", cid).put("nick", nick))
            }
        }
        emit(
            JSONObject().put("type", "track_room")
                .put("target", targetProfile ?: JSONObject())
                .put("room", room)
                .put("name", currentName)
                .put("privacy", currentPrivacy)
                .put("detectedBy", currentDetectedBy)
                .put("occupants", occupants)
                .put("total", occupants.length())
        )
    }

    private fun emitOccupants() {
        val room = currentRoom ?: return
        val occupants = JSONArray()
        synchronized(rosterLock) {
            roster.forEach { (cid, nick) ->
                occupants.put(JSONObject().put("cid", cid).put("nick", nick))
            }
        }
        emit(
            JSONObject().put("type", "track_occupants")
                .put("room", room)
                .put("occupants", occupants)
                .put("total", occupants.length())
        )
    }

    fun status(): JSONObject {
        return JSONObject()
            .put("loggedIn", api.loggedIn)
            .put("imq", imq.connected)
            .put("reconnecting", reconnecting)
            .put("lastEventAt", lastEventAt)
            .put("target", targetCid ?: "")
            .put("targetNick", targetNick)
            .put("room", currentRoom ?: "")
            .put("roomName", currentName)
            .put("occupants", synchronized(rosterLock) { roster.size })
            .put("error", api.lastError)
    }

    /** reaplica o nome da sala atual quando o config do site muda */
    fun refreshRoomLabel() {
        val rid = currentRoom ?: return
        pool.submit {
            if (currentRoom != rid) return@submit
            val label = api.roomLabel(rid)
            if (currentRoom == rid) {
                currentName = label.first
                currentPrivacy = label.second
                emitTrackRoom()
            }
        }
    }

    /** versao publica do where: so salas/IDs/tipo/contagem, sem nicks */
    private fun publicWhere(d: JSONObject?): JSONObject {
        if (d == null) return JSONObject()
        val rooms = d.optJSONArray("rooms") ?: JSONArray()
        val arr = JSONArray()
        for (i in 0 until rooms.length()) {
            val r = rooms.optJSONObject(i) ?: continue
            val m = JSONObject()
            m.put("roomId", r.optString("roomId"))
            m.put("name", r.optString("name"))
            m.put("privacy", r.optString("privacy"))
            m.put("occupancy", r.optInt("occupancy"))
            m.put("capacity", r.optInt("capacity"))
            m.put("people", r.optInt("people"))
            arr.put(m)
        }
        return JSONObject().put("rooms", arr)
    }

    /** snapshot publico para o site (sem nicks de alvo/ocupantes) */
    fun snapshot(): JSONObject {
        val out = status()
        out.remove("target")
        out.remove("targetNick")
        out.put("privacy", currentPrivacy)
        out.put("name", currentName)
        out.put("roomName", currentName)
        out.put("detectedBy", currentDetectedBy)
        out.put("where", publicWhere(lastWhere))
        return out
    }
}
