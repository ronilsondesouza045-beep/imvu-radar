package br.com.imvuradar.app.core

import android.util.Base64
import br.com.imvuradar.app.Config
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * IMQ (websocket do IMVU) - canal oficial de presenca.
 * Fica conectado SEMPRE: reconexao com backoff + watchdog (sem mensagem por 60s = derruba e religa).
 */
class ImqClient(private val onEvent: (JSONObject) -> Unit) {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var ws: WebSocket? = null
    private var attempt = 0
    private var lastMessageAt = 0L
    private var stopped = false
    private var watchdog: Thread? = null

    @Volatile var connected = false
        private set

    val watchedRooms = LinkedHashSet<String>()

    private fun b64(s: String): String = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    fun connect(cid: String, sessionId: String) {
        stopped = false
        openSocket(cid, sessionId)
        startWatchdog()
    }

    private fun openSocket(cid: String, sessionId: String) {
        if (stopped) return
        val req = Request.Builder().url(Config.WS).header("User-Agent", Config.UA).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0
                android.util.Log.i("IMVURadar", "imq: conectado, autenticando")
                val meta = JSONArray().put(
                    JSONObject()
                        .put("record", "metadata")
                        .put("key", "platform")
                        .put("value", b64("web"))
                )
                val connect = JSONObject()
                    .put("record", "msg_c2g_connect")
                    .put("user_id", cid)
                    .put("cookie", b64(sessionId))
                    .put("metadata", meta)
                webSocket.send(connect.toString())
                lastMessageAt = System.currentTimeMillis()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastMessageAt = System.currentTimeMillis()
                handle(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                android.util.Log.e("IMVURadar", "imq: falha -> ${t.message}")
                scheduleReconnect(cid, sessionId)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                android.util.Log.i("IMVURadar", "imq: fechado ($code) reagendando")
                scheduleReconnect(cid, sessionId)
            }
        })
    }

    private fun scheduleReconnect(cid: String, sessionId: String) {
        if (stopped) return
        val delays = longArrayOf(5, 15, 30, 60, 120, 300)
        val wait = delays[attempt.coerceAtMost(delays.size - 1)]
        attempt++
        Thread {
            Thread.sleep(wait * 1000)
            if (!stopped) openSocket(cid, sessionId)
        }.apply { isDaemon = true }.start()
    }

    private fun startWatchdog() {
        if (watchdog != null) return
        watchdog = Thread {
            while (!stopped) {
                Thread.sleep(15_000)
                val idle = System.currentTimeMillis() - lastMessageAt
                if (idle > 60_000) {
                    try { ws?.close(1000, "watchdog") } catch (_: Exception) { }
                    ws = null
                    connected = false
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun subscribe(roomId: String) {
        watchedRooms.add(roomId)
        if (connected) sendSubscribe(roomId)
    }

    private fun sendSubscribe(roomId: String) {
        val msg = JSONObject()
            .put("record", "msg_c2g_subscribe")
            .put("queues", JSONArray().put("inv:/chat/chat-$roomId"))
        ws?.send(msg.toString())
    }

    fun ping() {
        val msg = JSONObject().put("record", "msg_c2g_ping")
        ws?.send(msg.toString())
    }

    fun stop() {
        stopped = true
        try { ws?.close(1000, "bye") } catch (_: Exception) { }
        ws = null
        connected = false
    }

    private fun handle(text: String) {
        val m = try { JSONObject(text) } catch (e: Exception) { return }
        when (m.optString("record")) {
            "msg_g2c_result" -> {
                if (m.has("error") && !m.isNull("error")) {
                    android.util.Log.e("IMVURadar", "imq: erro de auth -> ${m.optString("error")}")
                    onEvent(JSONObject().put("type", "imq_error").put("message", m.optString("error")))
                } else {
                    connected = true
                    android.util.Log.i("IMVURadar", "imq: autenticado, floodgates abertos")
                    onEvent(JSONObject().put("type", "imq_ready"))
                    ws?.send(JSONObject().put("record", "msg_c2g_open_floodgates").toString())
                    watchedRooms.forEach { sendSubscribe(it) }
                }
            }
            "msg_g2c_send_message" -> {
                val raw = try {
                    String(Base64.decode(m.optString("message", ""), Base64.DEFAULT))
                } catch (e: Exception) { return }
                val obj = try { JSONObject(raw) } catch (e: Exception) { null } ?: return
                val action = obj.optString("action")
                if (action.isEmpty()) return
                val objs = obj.optJSONArray("objects") ?: JSONArray()
                val queue = m.optString("queue", "")
                val room = queue.substringAfter("chat-", "")
                for (i in 0 until objs.length()) {
                    val cid = objs.getString(i).substringAfterLast("user-")
                    onEvent(
                        JSONObject()
                            .put("type", "movement")
                            .put("action", action)
                            .put("room", room)
                            .put("cid", cid)
                    )
                }
            }
            "msg_g2c_joined_queue" -> Unit
        }
    }
}
