package br.com.imvuradar.app.core

import android.util.Log
import br.com.imvuradar.app.App
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Rastreio de salas publicas (hibrido com o tracker privado via IMQ).
 *
 * Roda em thread propria ao lado do Tracker.track: nao altera o fluxo privado.
 *  - varre as listas oficiais (explore/feature direto; recent/friends so depois
 *    de confirmar que a sala nao e privada)
 *  - achando o alvo numa publica: GET roster, baixa os retratos dos ocupantes
 *    para filesDir/avatars/<roomId>/ e emite o evento "public_room"
 *  - deep links oficiais da sala (imvu:// e web) vao no evento e na notificacao
 */
object PublicWatch {

    private const val TAG = "IMVURadar"
    private const val INTERVAL_MS = 20_000L
    private const val ROOM_CACHE_MS = 60_000L

    private val gen = AtomicInteger(0)
    private val lastRoom = HashMap<String, String>()
    @Volatile private var roomCacheAt = 0L
    @Volatile private var roomCache: List<String> = emptyList()

    fun start(targetCid: String, nick: String, onHit: (JSONObject) -> Unit) {
        val myGen = gen.incrementAndGet()
        synchronized(lastRoom) { lastRoom.remove(targetCid) }
        val api = Tracker.api
        val t = Thread {
            Log.i(TAG, "[public] vigiando salas publicas para $nick (cid $targetCid)")
            while (gen.get() == myGen) {
                try {
                    if (api.loggedIn) scan(api, targetCid, nick, myGen, onHit)
                } catch (e: Exception) {
                    Log.e(TAG, "[public] erro na varredura: ${e.message}")
                }
                try {
                    Thread.sleep(INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        t.isDaemon = true
        t.name = "radar-public"
        t.start()
    }

    fun stop() {
        gen.incrementAndGet()
    }

    // ---------------- varredura ----------------
    private fun scan(
        api: ImvuApi,
        targetCid: String,
        nick: String,
        myGen: Int,
        onHit: (JSONObject) -> Unit
    ) {
        val rooms = publicRooms(api)
        if (rooms.isEmpty()) return
        for (rid in rooms) {
            if (gen.get() != myGen) return
            val people = api.roster(rid)
            if (people.none { it.cid == targetCid }) continue

            val meta = api.roomJson(rid)
            // privada/restrita fica pro tracker privado (IMQ)
            if (meta.optString("privacy").equals("private", true)) continue
            if (meta.optBoolean("restricted", false)) continue

            val owner = meta.optString("owner").ifBlank {
                val head = rid.substringBefore("-")
                if (head.isNotEmpty() && head.all { it.isDigit() }) api.usernameOf(head) else ""
            }
            meta.put("owner", owner)
            val links = deepLinks(rid, owner, meta.optString("name"))
            val files = saveAvatars(api, rid, people)

            val anterior = synchronized(lastRoom) {
                val prev = lastRoom[targetCid]
                lastRoom[targetCid] = rid
                prev
            }
            if (anterior == rid) return

            logBlock(nick, meta, links, people, files)
            val evt = JSONObject()
                .put("type", "public_room")
                .put("target", JSONObject().put("cid", targetCid).put("nick", nick))
                .put("room", meta)
                .put("people", peopleJson(people, files))
                .put("links", links)
                .put("ts", System.currentTimeMillis())
            onHit(evt)
            return
        }
        synchronized(lastRoom) { lastRoom.remove(targetCid) }
    }

    /** ids de salas publicas das listas oficiais (cache curto) */
    private fun publicRooms(api: ImvuApi): List<String> {
        val now = System.currentTimeMillis()
        if (roomCache.isNotEmpty() && now - roomCacheAt < ROOM_CACHE_MS) return roomCache
        val ids = LinkedHashSet<String>()
        for (lid in listOf("explore", "feature")) ids.addAll(api.roomListRooms(lid))
        for (lid in listOf("recent", "friends")) {
            for (rid in api.roomListRooms(lid)) {
                val meta = api.roomMeta(rid) ?: continue
                if (!meta.optString("privacy").equals("private", true)) ids.add(rid)
            }
        }
        roomCache = ids.toList()
        roomCacheAt = now
        return roomCache
    }

    /** imvu://room/<dono>/<nome_com+> + link web oficial */
    fun deepLinks(rid: String, owner: String, name: String): JSONObject {
        val slug = URLEncoder.encode(name, "UTF-8") // espacos viram +
        val who = URLEncoder.encode(owner, "UTF-8")
        val imvu = if (owner.isNotBlank() && name.isNotBlank()) "imvu://room/$who/$slug" else ""
        val web = if (rid.isNotBlank()) "https://www.imvu.com/next/chat/room-$rid/" else ""
        return JSONObject()
            .put("imvu", imvu)
            .put("web", web)
            .put("owner", owner)
            .put("room_name", name)
            .put("roomId", rid)
    }

    // ---------------- avatares ----------------
    private fun saveAvatars(api: ImvuApi, rid: String, people: List<Person>): Map<String, String> {
        val dir = File(File(App.ctx.filesDir, "avatars"), safe(rid))
        try {
            if (!dir.exists()) dir.mkdirs()
        } catch (_: Exception) {
        }
        val out = HashMap<String, String>()
        for (p in people) {
            val url = p.portrait.ifBlank { p.avatar.ifBlank { p.render } }
            if (url.isBlank()) continue
            val base = safe(p.cid) + "_" + safe(p.username)
            val existente = listOf(".png", ".jpg", ".webp")
                .map { File(dir, base + it) }
                .firstOrNull { it.exists() && it.length() > 0 }
            if (existente != null) {
                out[p.cid] = existente.absolutePath
                continue
            }
            val bytes = api.authGetBytes(url) ?: continue
            if (bytes.isEmpty()) continue
            val ext = when {
                bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> ".jpg"
                bytes.size > 12 &&
                    bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                    bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() -> ".webp"
                else -> ".png"
            }
            val f = File(dir, base + ext)
            try {
                f.writeBytes(bytes)
                out[p.cid] = f.absolutePath
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun peopleJson(people: List<Person>, files: Map<String, String>): JSONArray {
        val arr = JSONArray()
        for (p in people) {
            arr.put(
                JSONObject()
                    .put("cid", p.cid)
                    .put("username", p.username)
                    .put("display_name", p.displayName)
                    .put("avatar", p.avatar)
                    .put("render", p.render)
                    .put("portrait", p.portrait)
                    .put("avatar_file", files[p.cid] ?: "")
            )
        }
        return arr
    }

    private fun logBlock(
        nick: String,
        meta: JSONObject,
        links: JSONObject,
        people: List<Person>,
        files: Map<String, String>
    ) {
        Log.i(TAG, "================ SALA PUBLICA ================")
        Log.i(TAG, "[PUBLIC] alvo $nick em \"${meta.optString("name")}\" (id ${meta.optString("roomId")})")
        Log.i(
            TAG,
            "  dono: ${meta.optString("owner")} | ocupacao: ${meta.optInt("occupancy")}/${meta.optInt("capacity")}" +
                " | privacidade: ${meta.optString("privacy")}"
        )
        if (links.optString("imvu").isNotEmpty()) Log.i(TAG, "  IMVU: ${links.optString("imvu")}")
        Log.i(TAG, "  WEB : ${links.optString("web")}")
        Log.i(TAG, "  ocupantes (${people.size}):")
        for (p in people) {
            Log.i(
                TAG,
                "    - ${p.displayName} [${p.username}] cid=${p.cid} -> ${files[p.cid] ?: "(sem avatar)"}"
            )
        }
        Log.i(TAG, "==============================================")
    }

    private fun safe(s: String, limit: Int = 64): String {
        val v = s.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_', '.', '-')
        return v.ifBlank { "sem_nome" }.take(limit)
    }
}
