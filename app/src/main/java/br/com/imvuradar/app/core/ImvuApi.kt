package br.com.imvuradar.app.core

import br.com.imvuradar.app.App
import br.com.imvuradar.app.Config
import br.com.imvuradar.app.RoomNames
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Person(
    val cid: String,
    val username: String,
    val displayName: String,
    val avatar: String,
    val render: String,
    val isVip: Boolean = false,
    val isAp: Boolean = false,
    val online: Boolean = false
)

/**
 * Cliente da API do IMVU (api.imvu.com) - porta do motor validado em Python.
 * Login -> sauce + cid + session_id; listas oficiais; roster; entrar/sair de sala.
 */
class ImvuApi {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val cookies = LinkedHashMap<String, String>()

    var sauce: String = ""
    var cid: String = ""
    var sessionId: String = ""
    var displayName: String = ""
    var lastError: String = ""
    var loggedIn: Boolean = false

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val _rosterCache = HashMap<String, Pair<Long, List<Person>>>()
    private val _userCache = HashMap<String, Pair<Long, String>>()
    private val _roomNames = HashMap<String, String>()

    private fun cookieHeader(): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun applyCookies(resp: okhttp3.Response) {
        val hs = resp.headers.values("Set-Cookie")
        for (h in hs) {
            val first = h.substringBefore(";")
            val idx = first.indexOf('=')
            if (idx > 0) cookies[first.substring(0, idx).trim()] = first.substring(idx + 1).trim()
        }
    }

    fun raw(
        path: String,
        method: String = "GET",
        body: String? = null,
        withSauce: Boolean = false
    ): Pair<Int, String>? {
        return try {
            val b = Request.Builder().url(Config.API + path)
            b.header("User-Agent", Config.UA)
            b.header("Accept", "application/json")
            if (cookies.isNotEmpty()) b.header("Cookie", cookieHeader())
            if (withSauce && sauce.isNotEmpty()) {
                b.header("x-imvu-sauce", sauce)
                b.header("x-imvu-application", "next_desktop/1")
            }
            when (method) {
                "POST" -> b.post((body ?: "{}").toRequestBody(jsonType))
                "DELETE" -> b.delete()
                "PUT" -> b.put((body ?: "{}").toRequestBody(jsonType))
                else -> b.get()
            }
            http.newCall(b.build()).execute().use { resp ->
                applyCookies(resp)
                resp.code to (resp.body?.string() ?: "")
            }
        } catch (e: Exception) {
            lastError = e.message ?: "erro de rede"
            null
        }
    }

    fun getJson(path: String): JSONObject? {
        val r = raw(path) ?: return null
        if (r.first !in 200..299) return null
        return try { JSONObject(r.second) } catch (e: Exception) { null }
    }

    private fun firstNode(env: JSONObject): JSONObject? {
        val den = env.optJSONObject("denormalized") ?: return null
        val keys = den.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val node = den.optJSONObject(k) ?: continue
            if (node.has("data")) return node
        }
        return null
    }

    // ---------------- login ----------------
    fun login(user: String, pass: String): Boolean {
        lastError = ""
        android.util.Log.i("IMVURadar", "login: tentando (user=${user.take(2)}***)")
        val payload = JSONObject()
            .put("gdpr_cookie_acceptance", true)
            .put("username", user)
            .put("password", pass)
            .put("remember_device", true)
        val res = raw("/login", "POST", payload.toString())
        if (res == null) {
            android.util.Log.e("IMVURadar", "login: excecao de rede: $lastError")
            lastError = "falha de rede: " + (lastError ?: "?")
            return false
        }
        val (code, text) = res
        android.util.Log.i("IMVURadar", "login: HTTP $code (${text.length} bytes)")
        if (code !in 200..299) {
            lastError = "login recusado (HTTP $code)"
            return false
        }
        val env = try { JSONObject(text) } catch (e: Exception) { null }
        if (env == null || env.optString("status") != "success") {
            lastError = "login negado"
            android.util.Log.e("IMVURadar", "login: resposta nao-success")
            return false
        }
        val data = firstNode(env)?.optJSONObject("data")
        if (data == null) {
            lastError = "resposta sem sauce"
            android.util.Log.e("IMVURadar", "login: sem no de dados")
            return false
        }
        sauce = data.optString("sauce")
        val me = getJson("/login/me")
        if (me == null) {
            lastError = "sessao (/login/me) falhou"
            android.util.Log.e("IMVURadar", "login: /login/me nulo")
            return false
        }
        val mnode = firstNode(me)
        cid = mnode?.optJSONObject("relations")?.optString("quick_chat_profile").orEmpty()
        sessionId = mnode?.optJSONObject("data")?.optString("session_id").orEmpty()
        if (cid.isEmpty() || sessionId.isEmpty()) {
            lastError = "sessao invalida (cid/session)"
            android.util.Log.e("IMVURadar", "login: cid/session vazio")
            return false
        }
        loggedIn = true
        android.util.Log.i("IMVURadar", "login OK (cid $cid, sauce ${sauce.length} chars)")
        return true
    }

    /** GET autenticado devolvendo bytes (renders do IMVU exigem sessao) */
    fun authGetBytes(url: String): ByteArray? {
        return try {
            val b = Request.Builder().url(url)
            b.header("User-Agent", Config.UA)
            b.header("Origin", "https://www.imvu.com")
            b.header("Referer", "https://www.imvu.com/")
            if (cookies.isNotEmpty()) b.header("Cookie", cookieHeader())
            http.newCall(b.build()).execute().use { resp -> resp.body?.bytes() }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- usuarios ----------------
    fun resolve(nick: String): Person? {
        val env = getJson("/user?username=" + java.net.URLEncoder.encode(nick, "UTF-8")) ?: return null
        val den = env.optJSONObject("denormalized") ?: return null
        val keys = den.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (!k.contains("/user/user-")) continue
            val d = den.optJSONObject(k)?.optJSONObject("data") ?: continue
            return Person(
                cid = k.substringAfterLast("user-"),
                username = d.optString("username", nick),
                displayName = d.optString("display_name", nick),
                avatar = d.optString("thumbnail_url", d.optString("avatar_image", "")),
                render = d.optString("avatar_image", ""),
                isVip = d.optBoolean("is_vip"),
                isAp = d.optBoolean("is_ap"),
                online = d.optBoolean("online")
            )
        }
        return null
    }

    fun online(cid: String): Boolean {
        val env = getJson("/presence/presence-$cid") ?: return false
        val n = firstNode(env) ?: return false
        return n.optJSONObject("data")?.optBoolean("online") ?: false
    }

    /** cid -> username, com cache. Usado pelo roster do IMQ. */
    fun usernameOf(cid: String): String {
        val now = System.currentTimeMillis()
        _userCache[cid]?.let { if (now - it.first < 900_000) return it.second }
        val env = getJson("/user/user-$cid")
        val name = env?.let {
            firstNode(it)?.optJSONObject("data")?.optString("username")
        }?.takeIf { !it.isNullOrBlank() } ?: cid
        _userCache[cid] = now to name
        return name
    }

    // ---------------- salas ----------------
    fun roomListRooms(listId: String): List<String> {
        val env = getJson("/room_list/room_list-$cid-$listId/rooms") ?: return emptyList()
        val den = env.optJSONObject("denormalized") ?: return emptyList()
        val out = mutableListOf<String>()
        val keys = den.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (!k.endsWith("/rooms")) continue
            val items = den.optJSONObject(k)?.optJSONObject("data")?.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val itemUrl = items.getString(i)
                val rid = itemUrl.substringAfterLast('/').removePrefix("room-")
                out.add(rid)
                // aproveita o nome que vem denormalizado na lista (quando vier)
                val d = den.optJSONObject(itemUrl)?.optJSONObject("data")
                val name = d?.optString("name") ?: ""
                if (name.isNotEmpty()) _roomNames[rid] = name
            }
        }
        return out
    }

    /** callback disparado a cada roster lido (o Tracker usa pra alimentar o indice) */
    var onRoster: ((String, List<String>) -> Unit)? = null

    fun roster(rid: String, ttl: Int = 0): List<Person> {
        val c = if (ttl > 0) _rosterCache.get(rid) else null
        if (c != null && (System.currentTimeMillis() - c.first) < 12_000) {
            onRoster?.invoke(rid, c.second.map { it.cid })
            return c.second
        }
        val people = mutableListOf<Person>()
        val j = getJson("/chat/chat-$rid/participants")
        if (j != null) {
            val den = j.optJSONObject("denormalized")
            if (den != null) {
                val keys = den.keys()
                var items = JSONArray()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k.endsWith("/participants")) {
                        items = den.optJSONObject(k)?.optJSONObject("data")?.optJSONArray("items") ?: JSONArray()
                    }
                }
                for (i in 0 until items.length()) {
                    val itemUrl = items.getString(i)
                    val node = den.optJSONObject(itemUrl) ?: continue
                    val ref = node.optJSONObject("relations")?.optString("ref").orEmpty()
                    val d = den.optJSONObject(ref)?.optJSONObject("data") ?: continue
                    people.add(
                        Person(
                            cid = ref.substringAfterLast("user-"),
                            username = d.optString("username", "?"),
                            displayName = d.optString("display_name", "?"),
                            avatar = d.optString("thumbnail_url", d.optString("avatar_image", "")),
                            render = d.optString("avatar_image", ""),
                            isVip = d.optBoolean("is_vip"),
                            isAp = d.optBoolean("is_ap"),
                            online = d.optBoolean("online")
                        )
                    )
                }
            }
        }
        _rosterCache[rid] = (System.currentTimeMillis() to people)
        onRoster?.invoke(rid, people.map { it.cid })
        return people
    }

    fun roomMeta(rid: String): JSONObject? {
        val r = raw("/room/room-$rid")
        if (r == null) return null
        if (r.first !in 200..299) {
            // 403 ROOM_NODE-047 = conteudo restrito (sala VIP / pessoal): so a conta dono enxerga
            var err = ""
            var msg = ""
            try {
                val j = JSONObject(r.second)
                err = j.optString("error")
                msg = j.optString("message")
            } catch (_: Exception) { }
            return JSONObject()
                .put("roomId", rid)
                .put("restricted", true)
                .put("error", err)
                .put("message", msg)
                .put("name", "")
        }
        val env = try { JSONObject(r.second) } catch (e: Exception) { return null }
        val den = env.optJSONObject("denormalized") ?: return null
        val keys = den.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val d = den.optJSONObject(k)?.optJSONObject("data") ?: continue
            if (d.has("name")) return d
        }
        return null
    }

    fun roomJson(rid: String): JSONObject {
        val m = roomMeta(rid) ?: JSONObject()
        val out = JSONObject()
        out.put("roomId", rid)
        out.put("restricted", m.optBoolean("restricted", false))
        out.put("error", m.optString("error", ""))
        out.put("message", m.optString("message", ""))
        out.put("name", m.optString("name", rid))
        out.put("privacy", m.optString("privacy", "?"))
        out.put("occupancy", m.optInt("occupancy", 0))
        out.put("capacity", m.optInt("capacity", 0))
        out.put("owner", m.optString("owner_avatarname", ""))
        var render = m.optString("rendered_image", "")
        if (render.startsWith("//")) render = "https:" + render
        out.put("render", render)
        var cover = m.optString("image_url", "")
        if (cover.startsWith("//")) cover = "https:" + cover
        out.put("cover", cover)
        return out
    }

    /** nome + privacidade. Sala privada de terceiro vem 403 -> apelido ou fallback. */
    fun roomLabel(rid: String): Pair<String, String> {
        val m = roomJson(rid)
        val n = rid.substringAfterLast('-', "0")
        val alias = try { RoomNames.get(App.ctx, rid) } catch (_: Exception) { "" }
        val cached = _roomNames[rid] ?: ""
        if (m.optBoolean("restricted")) {
            val name = alias.ifBlank {
                cached.ifBlank {
                    if (n == "1") "My Room" else "Sala privada $n"
                }
            }
            return Pair(name, "PRIVADA")
        }
        val name = alias.ifBlank {
            m.optString("name", "").ifBlank { cached.ifBlank { rid } }
        }
        val privacy = if (m.optString("privacy") == "private") "PRIVADA" else "PUBLICA"
        return Pair(name, privacy)
    }

    fun enterRoom(rid: String): Int {
        val body = JSONObject().put("id", "${Config.API}/user/user-$cid").toString()
        val r = raw("/chat/chat-$rid/participants", "POST", body, withSauce = true) ?: return 0
        return r.first
    }

    fun leaveRoom(rid: String): Int {
        val r = raw("/chat/chat-$rid/participants/user-$cid", "DELETE", null, withSauce = true) ?: return 0
        return r.first
    }

    fun peopleInLists(listIds: List<String>, limit: Int = 300): List<Person> {
        val seen = LinkedHashMap<String, Person>()
        for (lid in listIds) {
            for (rid in roomListRooms(lid)) {
                for (p in roster(rid)) seen.putIfAbsent(p.cid, p)
                if (seen.size >= limit) return seen.values.toList()
            }
        }
        return seen.values.toList()
    }

    /** acha em qual sala o cid esta, varrendo as listas oficiais do IMVU */
    fun findRoomFor(targetCid: String, listIds: List<String> = listOf("friends", "explore", "recent", "feature")): Pair<String, List<Person>>? {
        for (lid in listIds) {
            for (rid in roomListRooms(lid)) {
                val people = roster(rid)
                if (people.any { it.cid == targetCid }) return Pair(rid, people)
            }
        }
        return null
    }

    /** procura o cid nas salas PROPRIAS dele (ele nao e amigo da conta, logo nao aparece nas listas) */
    fun findInOwnRooms(targetCid: String, maxn: Int = 120): Pair<String, List<Person>>? {
        for (n in 1..maxn) {
            val rid = "${targetCid}-$n"
            val meta = roomMeta(rid) ?: continue
            val people = roster(rid)
            if (people.any { it.cid == targetCid }) return Pair(rid, people)
        }
        return null
    }

    /**
     * MINHAS SALAS: so funciona com a PROPRIA conta (as relations default_room /
     * recent_rooms de outra pessoa sao 403). Mostra quem esta na sua sala pessoal.
     */
    fun myRooms(): List<Pair<String, List<Person>>> {
        val j = getJson("/user/user-$cid") ?: return emptyList()
        val rel = HashMap<String, String>()
        val den = j.optJSONObject("denormalized")
        if (den != null) {
            val nk = den.keys()
            while (nk.hasNext()) {
                val v = den.optJSONObject(nk.next()) ?: continue
                val r = v.optJSONObject("relations") ?: continue
                val rk = r.keys()
                while (rk.hasNext()) {
                    val k = rk.next()
                    rel[k] = r.optString(k)
                }
            }
        }
        val ids = LinkedHashSet<String>()
        for (k in listOf("default_room", "photo_booth_room")) {
            val u = rel[k]
            if (u != null && u.contains("/room/room-")) ids.add(u.substringAfterLast("/room/room-"))
        }
        val recent = rel["recent_rooms"]
        if (recent != null) {
            val rr = getJson(recent.replace(Config.API, ""))
            val d2 = rr?.optJSONObject("denormalized")
            if (d2 != null) {
                val nk2 = d2.keys()
                while (nk2.hasNext()) {
                    val v = d2.optJSONObject(nk2.next()) ?: continue
                    val items = v.optJSONObject("data")?.optJSONArray("items") ?: continue
                    for (i in 0 until items.length()) {
                        val rid = items.getString(i).substringAfterLast("/room/room-")
                        if (rid.isNotEmpty()) ids.add(rid)
                    }
                }
            }
        }
        val out = mutableListOf<Pair<String, List<Person>>>()
        for (rid in ids.take(12)) {
            val people = roster(rid)
            if (people.isNotEmpty()) out.add(Pair(rid, people))
        }
        return out
    }

    /**acha em quais salas o cid esta - TODAS (a pessoa pode estar em varias ao mesmo tempo) */
    /**
     * Varre <cid>-1..<cid>-maxn e devolve as salas OCUPADAS do dono.
     * O no da sala e publico (nao precisa de login) e o ROSTER de uma sala
     * privada tambem e legivel com sessao - por isso da pra caçar em sala privada.
     */
    fun roomsOfOwner(
        cid: String,
        maxn: Int = 400,
        onlyOccupied: Boolean = true,
        onProgress: ((String) -> Unit)? = null
    ): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        for (n in 1..maxn) {
            val rid = "$cid-$n"
            val meta = roomMeta(rid) ?: continue
            val occ = meta.optInt("occupancy", 0)
            if (onlyOccupied && occ <= 0) continue
            meta.put("roomId", rid)
            out.add(meta)
            onProgress?.invoke("sala $rid: ${meta.optString("name")} ($occ)")
        }
        return out
    }

    /** procura o alvo em TODAS as salas (privadas inclusive) de um dono */
    fun findInOwnerRooms(
        targetCid: String,
        ownerCid: String,
        maxn: Int = 400,
        onProgress: ((String) -> Unit)? = null
    ): List<Triple<String, JSONObject, List<Person>>> {
        val hits = mutableListOf<Triple<String, JSONObject, List<Person>>>()
        for (m in roomsOfOwner(ownerCid, maxn, true, onProgress)) {
            val rid = m.optString("roomId")
            val people = roster(rid)
            if (people.any { it.cid == targetCid }) {
                hits.add(Triple(rid, m, people))
                onProgress?.invoke("ACHEI $targetCid em $rid")
            }
        }
        return hits
    }

    fun findAllRoomsFor(
        targetCid: String,
        listIds: List<String> = listOf("friends", "explore", "recent", "feature"),
        onHit: ((String, List<Person>) -> Unit)? = null,
        deadlineMs: Long = 0,
        onRoom: ((String) -> Unit)? = null
    ): List<Pair<String, List<Person>>> {
        val out = mutableListOf<Pair<String, List<Person>>>()
        val seen = mutableSetOf<String>()
        for (lid in listIds) {
            for (rid in roomListRooms(lid)) {
                if (deadlineMs > 0 && System.currentTimeMillis() > deadlineMs) return out
                if (!seen.add(rid)) continue
                // assina a sala mesmo sem roster legivel: o IMQ pode provar
                // a presenca do alvo (sala privada/VIP) pelo edge:participants
                onRoom?.invoke(rid)
                val people = roster(rid)
                if (people.any { it.cid == targetCid }) {
                    out.add(Pair(rid, people))
                    onHit?.invoke(rid, people)
                }
            }
        }
        return out
    }

    /** TODAS as salas proprias onde o cid esta */
    fun findAllInOwnRooms(targetCid: String, maxn: Int = 120): List<Pair<String, List<Person>>> {
        val out = mutableListOf<Pair<String, List<Person>>>()
        for (n in 1..maxn) {
            val rid = "${targetCid}-$n"
            val meta = roomMeta(rid) ?: continue
            val people = roster(rid)
            if (people.any { it.cid == targetCid }) out.add(Pair(rid, people))
        }
        return out
    }

    /** busca profunda: 1 salto a partir das pessoas que estao nas salas conhecidas (TODAS as salas achadas) */
    fun deepScan(
        targetCid: String,
        peopleLimit: Int = 25,
        roomsPer: Int = 25,
        progress: ((String) -> Unit)? = null,
        extraCids: List<String> = emptyList()
    ): List<Pair<String, List<Person>>> {
        val out = mutableListOf<Pair<String, List<Person>>>()
        val cands = peopleInLists(listOf("friends", "explore", "recent", "feature"), peopleLimit).toMutableList()
        val cidSet = cands.map { it.cid }.toMutableSet()
        for (c in extraCids) if (cidSet.add(c)) cands.add(Person(c, c, c, "", ""))
        progress?.invoke("pessoas candidatas: ${cands.size}")
        var done = 0
        for (person in cands) {
            if (person.cid == targetCid) continue
            for (n in 1..roomsPer) {
                val rid = "${person.cid}-$n"
                val meta = roomMeta(rid) ?: continue
                val people = roster(rid)
                if (people.any { it.cid == targetCid }) {
                    out.add(Pair(rid, people))
                    progress?.invoke("achei em $rid (total ${out.size})")
                }
            }
            done++
            if (done % 5 == 0) progress?.invoke("varridas $done/${cands.size} pessoas")
        }
        progress?.invoke("terminou: $done pessoas, ${out.size} sala(s) com o alvo")
        return out
    }
}
