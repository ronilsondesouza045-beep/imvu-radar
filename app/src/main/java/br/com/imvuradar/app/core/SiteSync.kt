package br.com.imvuradar.app.core

import br.com.imvuradar.app.Config
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ponte com o site na Vercel:
 *  - envia status/roster do APK para /api/status
 *  - busca nomes de sala em /api/config
 *  - busca comandos (track/stop) em /api/command
 */
object SiteSync {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val pool = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var started = false
    @Volatile private var lastCommandId = ""

    fun start() {
        if (started) return
        started = true
        pool.scheduleWithFixedDelay({ tick() }, 5, 10, TimeUnit.SECONDS)
    }

    private fun get(path: String): JSONObject? {
        val req = Request.Builder().url(Config.SITE_URL + path)
            .header("Content-Type", "application/json")
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else JSONObject(r.body?.string() ?: "{}")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun post(path: String, body: JSONObject) {
        val req = Request.Builder().url(Config.SITE_URL + path)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(req).execute().use { _ -> }
        } catch (_: Exception) {
        }
    }

    private fun tick() {
        try {
            post("/api/status", Tracker.snapshot())
            val cfg = get("/api/config")
            if (cfg != null && RemoteConfig.update(cfg)) Tracker.refreshRoomLabel()
            val cmd = get("/api/command") ?: return
            val id = cmd.optString("id")
            if (id.isNotEmpty() && id != lastCommandId) {
                lastCommandId = id
                when (cmd.optString("action")) {
                    "track" -> Tracker.track(cmd.optString("nick"))
                    "stop" -> Tracker.stopTrack()
                    "where" -> Tracker.where(cmd.optString("nick"))
                }
            }
        } catch (_: Exception) {
        }
    }
}
