package br.com.imvuradar.app.core

import br.com.imvuradar.app.App
import br.com.imvuradar.app.Config
import br.com.imvuradar.app.SecureStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Orquestrador: login, onde esta, amigos, sala, forca entrada, varreduras e o IMQ sempre vivo.
 * Nao expoe identidade da conta em nenhum evento (a UI nao mostra quem e o bot).
 */
object Tracker {

    val api = ImvuApi()
    val imq = ImqClient { evt -> emit(evt) }
    val index: RoomIndex by lazy { RoomIndex(App.ctx) }

    private val pool = Executors.newFixedThreadPool(4)
    private val store: SecureStore by lazy { SecureStore(App.ctx) }

    @Volatile var listener: ((JSONObject) -> Unit)? = null

    @Volatile var started = false
    @Volatile var lastEventAt = 0L
    @Volatile var reconnecting = false

    val lastSeen = LinkedHashMap<String, JSONObject>()
    val watchTargets = LinkedHashMap<String, String>()
    private var credentials: Pair<String, String>? = null

    fun emit(evt: JSONObject) {
        lastEventAt = System.currentTimeMillis()
        try {
            if (evt.optString("type") == "movement") {
                val cid = evt.optString("cid")
                lastSeen[cid] = JSONObject()
                    .put("room", evt.optString("room"))
                    .put("action", evt.optString("action"))
                    .put("ts", lastEventAt)
                watchTargets[cid]?.let { nick ->
                    val p = api.resolve(cid)
                    emit(
                        JSONObject()
                            .put("type", "alert")
                            .put("cid", cid)
                            .put("nick", nick)
                            .put("room", evt.optString("room"))
                            .put("action", evt.optString("action"))
                            .put("person", personJson(p))
                    )
                }
            }
            listener?.invoke(evt)
        } catch (_: Exception) {
        }
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
        // cada roster lido alimenta o indice (cid -> salas)
        api.onRoster = { rid, cids -> index.updateRoom(rid, cids) }
        // conta guardada (se o usuario trocou) senao a conta padrao embutida
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
                    emit(JSONObject().put("type", "ready").put("imq", true))
                    imq.connect(api.cid, api.sessionId)
                    imq.watchedRooms.forEach { imq.subscribe(it) }
                    return@submit
                }
                reconnecting = true
                emit(JSONObject().put("type", "login_error").put("message", api.lastError))
                tries++
                Thread.sleep((if (tries > 5) 30_000 else 8_000).toLong())
            }
        }
        // ping periodico do IMQ
        pool.submit {
            while (true) {
                Thread.sleep(15_000)
                try { imq.ping() } catch (_: Exception) { }
            }
        }
        startScanner()
    }

    fun setCredentials(user: String, pass: String) {
        store.saveCredentials(user, pass)
        credentials = Pair(user, pass)
        pool.submit {
            if (api.login(user, pass)) {
                emit(JSONObject().put("type", "ready").put("imq", true))
                imq.connect(api.cid, api.sessionId)
            } else {
                emit(JSONObject().put("type", "login_error").put("message", api.lastError))
            }
        }
    }

    fun hasCredentials(): Boolean = store.hasCredentials()

    // ---------------- operacoes ----------------
    /**
     * SCANNER: mantem o indice atualizado (cid -> salas) e assina o IMQ de cada
     * sala pra receber movimento ao vivo. Roda em ciclos curtos pra nao estourar
     * rate limit da conta. E o que permite achar alguem em VARIAS salas ao mesmo tempo.
     */
    fun startScanner() {
        pool.submit {
            var cursor = 0
            while (true) {
                try {
                    if (!api.loggedIn) {
                        Thread.sleep(5_000)
                        continue
                    }
                    val known = mutableSetOf<String>()
                    for (lid in listOf("friends", "recent", "explore")) {
                        try { known.addAll(api.roomListRooms(lid)) } catch (_: Exception) { }
                    }
                    known.addAll(index.knownRooms())

                    val list = known.toList()
                    if (list.isNotEmpty()) {
                        val slice = minOf(25, list.size)
                        for (k in 0 until slice) {
                            val rid = list[(cursor + k) % list.size]
                            try {
                                val people = api.roster(rid)
                                imq.subscribe(rid)
                                if (people.any { it.isVip && it.online }) {
                                    // nada extra: so alimenta o indice via onRoster
                                }
                            } catch (_: Exception) { }
                            Thread.sleep(120)
                        }
                        cursor = (cursor + slice) % list.size
                    }
                    emit(
                        JSONObject()
                            .put("type", "scanner")
                            .put("rooms", known.size)
                            .put("indexed", index.knownCids().size)
                            .put("knownRooms", index.knownRooms().size)
                    )
                } catch (e: Exception) {
                    android.util.Log.e("IMVURadar", "scanner: ${e.message}")
                }
                Thread.sleep(75_000)
            }
        }
    }

    fun where(nick: String) {
        pool.submit {
            val p = api.resolve(nick)
            if (p == null) {
                emit(JSONObject().put("type", "where").put("nick", nick).put("error", "nick nao encontrado"))
                return@submit
            }

            // 1) RESPOSTA INSTANTANEA pelo indice (sala onde ele ja estava)
            val knownRooms = index.roomsOf(p.cid)
            if (knownRooms.isNotEmpty()) {
                val hits = mutableListOf<Pair<String, List<Person>>>()
                for (rid in knownRooms) {
                    val people = api.roster(rid)
                    if (people.any { it.cid == p.cid }) hits.add(Pair(rid, people))
                }
                if (hits.isNotEmpty()) emitWhere(p, hits.toList())
            }

            // 2) FASE 1 (rapida, com emissao progressiva): listas do IMVU
            val hits = mutableListOf<Pair<String, List<Person>>>()
            val deadline = System.currentTimeMillis() + 25_000
            api.findAllRoomsFor(
                p.cid,
                listOf("friends", "recent", "explore"),
                onHit = { rid, people ->
                    hits.add(Pair(rid, people))
                    emitWhere(p, hits.toList())
                },
                deadlineMs = deadline
            )
            emitWhere(p, hits.toList())

            // 3) FASE 2 (background): salas PROPRIAS do alvo + busca funda com o indice
            pool.submit {
                try {
                    val own = api.findAllInOwnRooms(p.cid, 120)
                    val known = hits.map { it.first }.toMutableSet()
                    own.forEach { if (known.add(it.first)) hits.add(it) }
                    emitWhere(p, hits.toList())

                    if (hits.isEmpty()) {
                        val deep = api.deepScan(p.cid, 25, 25, null, index.knownCids())
                        val known2 = hits.map { it.first }.toMutableSet()
                        deep.forEach { if (known2.add(it.first)) hits.add(it) }
                        if (deep.isNotEmpty()) emitWhere(p, hits.toList())
                    }
                } catch (e: Exception) {
                    android.util.Log.e("IMVURadar", "fase2: ${e.message}")
                }
            }
        }
    }

    private fun emitWhere(p: Person, hits: List<Pair<String, List<Person>>>) {
        val rooms = org.json.JSONArray()
        var first: JSONObject? = null
        for (h in hits) {
            val meta = api.roomJson(h.first)
            meta.put("people", h.second.size)
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

    fun friends() {
        pool.submit {
            val env = api.getJson("/user/user-${api.cid}/friends?limit=200")
            val den = env?.optJSONObject("denormalized")
            val out = JSONArray()
            if (den != null) {
                val keys = den.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (!k.contains("/user/user-")) continue
                    val node = den.optJSONObject(k) ?: continue
                    if (!node.optJSONObject("relations")?.has("viewer_friend")!!) continue
                    val d = node.optJSONObject("data") ?: continue
                    val cid = k.substringAfterLast("user-")
                    val found = api.findRoomFor(cid, listOf("friends"))
                    val room = if (found != null) api.roomJson(found.first).put("people", found.second.size) else JSONObject()
                    out.put(
                        JSONObject()
                            .put("cid", cid)
                            .put("username", d.optString("username", "?"))
                            .put("display_name", d.optString("display_name", "?"))
                            .put("avatar", d.optString("thumbnail_url", d.optString("avatar_image", "")))
                            .put("render", d.optString("avatar_image", ""))
                            .put("online", d.optBoolean("online"))
                            .put("room", room)
                    )
                }
            }
            emit(JSONObject().put("type", "friends").put("data", out))
        }
    }

    fun room(rid: String) {
        pool.submit {
            val meta = api.roomJson(rid)
            val people = api.roster(rid)
            meta.put("people", people.size)
            imq.subscribe(rid)
            emit(
                JSONObject()
                    .put("type", "room")
                    .put("rid", rid)
                    .put("meta", meta)
                    .put("people", peopleJson(people))
            )
        }
    }

    fun enter(rid: String) {
        pool.submit {
            val code = api.enterRoom(rid)
            if (code in 200..299) {
                imq.subscribe(rid)
                val meta = api.roomJson(rid)
                val people = api.roster(rid)
                emit(
                    JSONObject()
                        .put("type", "enter")
                        .put("rid", rid)
                        .put("ok", true)
                        .put("meta", meta)
                        .put("people", peopleJson(people))
                )
            } else {
                val reason = when (code) {
                    400 -> "sala cheia (400)"
                    403 -> "sem permissao (403)"
                    0 -> "sem conexao"
                    else -> "falhou ($code)"
                }
                emit(JSONObject().put("type", "enter").put("rid", rid).put("ok", false).put("reason", reason))
            }
        }
    }

    fun leave(rid: String) {
        pool.submit {
            val code = api.leaveRoom(rid)
            emit(JSONObject().put("type", "leave").put("rid", rid).put("ok", code in 200..299))
        }
    }

    fun watchTarget(nick: String) {
        pool.submit {
            val p = api.resolve(nick)
            if (p == null) {
                emit(JSONObject().put("type", "log").put("msg", "alvo nao encontrado"))
                return@submit
            }
            watchTargets[p.cid] = p.username
            emit(JSONObject().put("type", "log").put("msg", "alerta ao vivo ligado"))
        }
    }

    fun lastSeen(cid: String): JSONObject {
        val ls = lastSeen[cid]
        return if (ls == null) JSONObject().put("cid", cid).put("seen", false)
        else JSONObject().put("cid", cid).put("seen", true).put("room", ls.optString("room"))
            .put("action", ls.optString("action")).put("ts", ls.optLong("ts"))
    }

    /** varredura por salas do dono: procura o alvo dentro */
    fun scan(target: String, owners: List<String>, max: Int) {
        pool.submit {
            val tp = api.resolve(target)
            if (tp == null) {
                emit(JSONObject().put("type", "scan_done").put("error", "alvo nao encontrado"))
                return@submit
            }
            val jid = "scan-${System.currentTimeMillis()}"
            emit(JSONObject().put("type", "scan_start").put("jid", jid).put("target", personJson(tp)))
            val names = (listOf(target) + owners).distinct()
            for (name in names) {
                val op = api.resolve(name) ?: continue
                emit(
                    JSONObject().put("type", "scan_progress").put("jid", jid)
                        .put("msg", "varrendo salas de $name (cid ${op.cid})...")
                )
                for (n in 1..max) {
                    val rid = "${op.cid}-$n"
                    val meta = api.roomMeta(rid) ?: continue
                    val people = api.roster(rid)
                    if (people.any { it.cid == tp.cid }) {
                        val m = api.roomJson(rid).put("people", people.size)
                        emit(
                            JSONObject().put("type", "scan_hit").put("jid", jid)
                                .put("meta", m).put("people", peopleJson(people)).put("target", personJson(tp))
                        )
                        return@submit
                    }
                }
            }
            emit(JSONObject().put("type", "scan_done").put("jid", jid))
        }
    }

    /** busca profunda: 1 salto a partir das pessoas que estao nas salas conhecidas */
    fun deepScan(target: String, peopleLimit: Int, roomsPer: Int) {
        pool.submit {
            val tp = api.resolve(target)
            if (tp == null) {
                emit(JSONObject().put("type", "deep_done").put("error", "alvo nao encontrado"))
                return@submit
            }
            watchTargets[tp.cid] = tp.username
            val jid = "deep-${System.currentTimeMillis()}"
            emit(JSONObject().put("type", "deep_start").put("jid", jid).put("target", personJson(tp)))
            val cands = api.peopleInLists(listOf("friends", "explore", "recent", "feature"), peopleLimit)
            emit(
                JSONObject().put("type", "deep_progress").put("jid", jid)
                    .put("msg", "pessoas candidatas: ${cands.size}")
            )
            var done = 0
            for (person in cands) {
                if (person.cid == tp.cid) continue
                for (n in 1..roomsPer) {
                    val rid = "${person.cid}-$n"
                    val meta = api.roomMeta(rid) ?: continue
                    val people = api.roster(rid)
                    if (people.any { it.cid == tp.cid }) {
                        val m = api.roomJson(rid).put("people", people.size)
                        emit(
                            JSONObject().put("type", "deep_hit").put("jid", jid)
                                .put("meta", m).put("people", peopleJson(people)).put("target", personJson(tp))
                        )
                        return@submit
                    }
                }
                done++
                if (done % 5 == 0) {
                    emit(
                        JSONObject().put("type", "deep_progress").put("jid", jid)
                            .put("msg", "varridas $done/${cands.size} pessoas")
                    )
                }
            }
            emit(JSONObject().put("type", "deep_done").put("jid", jid).put("scanned", done))
        }
    }

    fun status(): JSONObject {
        return JSONObject()
            .put("loggedIn", api.loggedIn)
            .put("imq", imq.connected)
            .put("reconnecting", reconnecting)
            .put("lastEventAt", lastEventAt)
            .put("monitored", imq.watchedRooms.size)
            .put("error", api.lastError)
    }
}
