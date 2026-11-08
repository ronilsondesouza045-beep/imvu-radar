package br.com.imvuradar.app.core

import br.com.imvuradar.app.App
import br.com.imvuradar.app.Config
import br.com.imvuradar.app.Notifications
import br.com.imvuradar.app.SecureStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

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

    private val pool = Executors.newFixedThreadPool(4)
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
    @Volatile private var settingRoom: String? = null
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

            synchronized(this) {
                targetCid = p.cid
                targetNick = p.username
                targetProfile = personJson(p)
                currentRoom = null
                currentName = ""
                currentPrivacy = ""
                settingRoom = null
                roomSeq++
                synchronized(rosterLock) { roster.clear() }
            }

            imq.setTarget(p.cid)
            emit(JSONObject().put("type", "track_start").put("target", personJson(p)))
            emit(JSONObject().put("type", "track_status").put("msg", "conectando ao IMQ e procurando a sala..."))

            // probe de liveness: conexao nova, status 0 nas filas chat-<alvo>-*
            pool.submit { probeLoop(p.cid) }

            // busca inicial nas listas/salas do alvo (publicas). O IMQ confirma
            // a sala real e entrega o roster de privada.
            val hits = mutableListOf<Pair<String, List<Person>>>()
            try {
                hits.addAll(
                    api.findAllRoomsFor(
                        p.cid,
                        listOf("friends", "recent", "explore"),
                        deadlineMs = System.currentTimeMillis() + 15_000
                    )
                )
            } catch (_: Exception) { }
            try { hits.addAll(api.findAllInOwnRooms(p.cid, 120)) } catch (_: Exception) { }

            for (h in hits) imq.subscribeRoom(h.first)
            if (currentRoom == null && hits.isNotEmpty()) {
                setCurrentRoom(hits.first().first, hits.first().second)
            } else if (currentRoom == null) {
                emit(JSONObject().put("type", "track_status").put("msg", "aguardando evento do IMQ..."))
            }
        }
    }

    private fun probeLiveness(target: String, callback: (List<String>) -> Unit) {
        val probe = ImqClient { }
        probe.onReady = {
            probe.probeQueues(target) { live ->
                callback(live)
                probe.stop()
            }
        }
        probe.connect(api.cid, api.sessionId, botUser)
    }

    private fun probeLoop(target: String) {
        while (targetCid == target) {
            val latch = java.util.concurrent.CountDownLatch(1)
            val ref = java.util.concurrent.atomic.AtomicReference<List<String>>(emptyList())
            probeLiveness(target) { live ->
                ref.set(live)
                latch.countDown()
            }
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
            if (targetCid != target) break
            val live = ref.get()
            if (live.isNotEmpty()) {
                if (currentRoom == null && live.size == 1) {
                    setCurrentRoom(live[0])
                } else if (currentRoom == null && live.size > 1) {
                    emit(JSONObject().put("type", "track_status").put("msg", "varias salas ativas; aguardando evento..."))
                } else if (live.size == 1 && currentRoom != live[0]) {
                    setCurrentRoom(live[0])
                }
            } else if (currentRoom == null) {
                emit(JSONObject().put("type", "track_status").put("msg", "aguardando evento do IMQ..."))
            }
            Thread.sleep(15_000)
        }
    }

    fun stopTrack() {
        synchronized(this) {
            targetCid = null
            targetNick = ""
            targetProfile = null
            currentRoom = null
            currentName = ""
            currentPrivacy = ""
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
                        synchronized(this) {
                            roomSeq++
                            currentRoom = null
                            currentName = ""
                            currentPrivacy = ""
                            settingRoom = null
                            synchronized(rosterLock) { roster.clear() }
                        }
                        emit(JSONObject().put("type", "track_left").put("room", room))
                        Notifications.alert(
                            App.ctx, "SAIU DA SALA",
                            "$targetNick saiu da sala de ID $room"
                        )
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

    private fun setCurrentRoom(room: String, seed: List<Person>? = null) {
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
        // notificacao no celular: alvo ja estava / entrou / mudou de sala
        val tipo = if (currentPrivacy == "PRIVADA") "SALA PRIVADA" else "SALA PÚBLICA"
        Notifications.alert(
            App.ctx, tipo,
            "$targetNick está na sala \"$currentName\" (ID: $room)"
        )
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
}
