package br.com.imvuradar.app.core

import br.com.imvuradar.app.Config
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
                out.add(items.getString(i).substringAfterLast('/').removePrefix("room-"))
            }
        }
        return out
    }

    fun roster(rid: String): List<Person> {
        val env = getJson("/chat/chat-$rid/participants") ?: return emptyList()
        val den = env.optJSONObject("denormalized") ?: return emptyList()
        val keys = den.keys()
        var items = JSONArray()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.endsWith("/participants")) {
                items = den.optJSONObject(k)?.optJSONObject("data")?.optJSONArray("items") ?: JSONArray()
            }
        }
        val out = mutableListOf<Person>()
        for (i in 0 until items.length()) {
            val itemUrl = items.getString(i)
            val node = den.optJSONObject(itemUrl) ?: continue
            val ref = node.optJSONObject("relations")?.optString("ref").orEmpty()
            val d = den.optJSONObject(ref)?.optJSONObject("data") ?: continue
            out.add(
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
        return out
    }

    fun roomMeta(rid: String): JSONObject? {
        val env = getJson("/room/room-$rid") ?: return null
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

    /** busca profunda: 1 salto a partir das pessoas que estao nas salas conhecidas */
    fun deepScan(
        targetCid: String,
        peopleLimit: Int = 25,
        roomsPer: Int = 25,
        progress: ((String) -> Unit)? = null
    ): Pair<String, List<Person>>? {
        val cands = peopleInLists(listOf("friends", "explore", "recent", "feature"), peopleLimit)
        progress?.invoke("pessoas candidatas: ${cands.size}")
        var done = 0
        for (person in cands) {
            if (person.cid == targetCid) continue
            for (n in 1..roomsPer) {
                val rid = "${person.cid}-$n"
                val meta = roomMeta(rid) ?: continue
                val people = roster(rid)
                if (people.any { it.cid == targetCid }) {
                    progress?.invoke("achei em $rid")
                    return Pair(rid, people)
                }
            }
            done++
            if (done % 5 == 0) progress?.invoke("varridas $done/${cands.size} pessoas")
        }
        progress?.invoke("terminou: $done pessoas, nao achado")
        return null
    }
}
