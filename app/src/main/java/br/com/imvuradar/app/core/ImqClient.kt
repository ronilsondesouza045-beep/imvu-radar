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
 *
 * Mantem:
 *  - conexao com reconexao + watchdog
 *  - assinatura da faixa chat-<cid_alvo>-1..800 + fila do usuario
 *  - assinatura dinamica da sala atual (inclusive de outro dono)
 *  - keepalive de reassinatura a cada 2s (fila morta precisa ser reassinada)
 *  - eventos edge:participants -> presence {room, action, cid}
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
    private var keepalive: Thread? = null

    private var cid = ""
    private var sessionId = ""
    private var authed = false
    private var opId = 1000
    private var connectOp = 0
    private val pending = java.util.concurrent.ConcurrentHashMap<Int, (Int) -> Unit>()

    /** chamado quando o connect recebe status 0 (usado pelo probe) */
    var onReady: (() -> Unit)? = null

    @Volatile var connected = false
        private set

    @Volatile var targetCid: String? = null
        private set

    private val targetQueues = LinkedHashSet<String>()
    private val extraQueues = LinkedHashSet<String>()

    private fun nextOp(): Int {
        opId++
        return opId
    }

    private fun b64(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    fun connect(cid: String, sessionId: String) {
        this.cid = cid
        this.sessionId = sessionId
        stopped = false
        authed = false
        connected = false
        openSocket()
        startWatchdog()
        startKeepalive()
    }

    fun setTarget(target: String) {
        targetCid = target
        targetQueues.clear()
        for (n in 1..800) targetQueues.add("inv:/chat/chat-$target-$n")
        targetQueues.add("inv:/user/user-$target")
        extraQueues.clear()
        if (authed) resubscribe()
    }

    fun clearTarget() {
        targetCid = null
        targetQueues.clear()
        extraQueues.clear()
    }

    /** assina a sala atual (publica ou privada, de qualquer dono) */
    fun subscribeRoom(roomId: String) {
        if (roomId.isBlank()) return
        extraQueues.add("inv:/chat/chat-$roomId")
        if (authed) sendQueues(listOf("inv:/chat/chat-$roomId"))
    }

    fun clearRooms() {
        extraQueues.clear()
    }

    private fun openSocket() {
        if (stopped) return
        val req = Request.Builder().url(Config.WS).header("User-Agent", Config.UA).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0
                authed = false
                android.util.Log.i("IMVURadar", "imq: conectado, autenticando")
                val meta = JSONArray().put(
                    JSONObject()
                        .put("record", "metadata")
                        .put("key", "platform")
                        .put("value", b64("web"))
                )
                connectOp = nextOp()
                val connect = JSONObject()
                    .put("record", "msg_c2g_connect")
                    .put("user_id", cid)
                    .put("cookie", b64(sessionId))
                    .put("metadata", meta)
                    .put("op_id", connectOp)
                webSocket.send(connect.toString())
                lastMessageAt = System.currentTimeMillis()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastMessageAt = System.currentTimeMillis()
                handle(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                authed = false
                android.util.Log.e("IMVURadar", "imq: falha -> ${t.message}")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                authed = false
                android.util.Log.i("IMVURadar", "imq: fechado ($code) reagendando")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (stopped) return
        val delays = longArrayOf(5, 15, 30, 60, 120, 300)
        val wait = delays[attempt.coerceAtMost(delays.size - 1)]
        attempt++
        Thread {
            Thread.sleep(wait * 1000)
            if (!stopped) openSocket()
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
                    authed = false
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun startKeepalive() {
        if (keepalive != null) return
        keepalive = Thread {
            while (!stopped) {
                Thread.sleep(2000)
                if (authed) resubscribe()
            }
        }.apply { isDaemon = true; start() }
    }

    private fun resubscribe() {
        val all = LinkedHashSet<String>()
        all.addAll(targetQueues)
        all.addAll(extraQueues)
        if (all.isNotEmpty()) sendQueues(all.toList())
    }

    private fun sendQueues(queues: List<String>) {
        for (chunk in queues.chunked(400)) {
            val frames = JSONArray()
            for (q in chunk) {
                frames.put(
                    JSONObject()
                        .put("record", "subscription")
                        .put("name", q)
                        .put("op_id", nextOp())
                )
            }
            val msg = JSONObject()
                .put("record", "msg_c2g_subscribe")
                .put("queues_with_results", frames)
            ws?.send(msg.toString())
        }
    }

    /**
     * Probe de liveness: conexao NOVA mede status 0/1 de chat-<alvo>-1..800.
     * status 0 = fila viva = tem gente dentro daquela sala AGORA.
     */
    fun probeQueues(target: String, timeoutMs: Long = 25_000, callback: (List<String>) -> Unit) {
        val queues = (1..800).map { "inv:/chat/chat-$target-$it" }
        val live = java.util.Collections.synchronizedList(mutableListOf<String>())
        val remaining = java.util.concurrent.atomic.AtomicInteger(queues.size)
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val finish = {
            if (done.compareAndSet(false, true)) callback(live.toList())
        }
        val pairs = queues.map { q -> q to nextOp() }
        for ((q, op) in pairs) {
            pending[op] = { status ->
                if (status == 0) live.add(q.removePrefix("inv:/chat/chat-"))
                if (remaining.decrementAndGet() == 0) finish()
            }
        }
        for (chunk in pairs.chunked(400)) {
            val frames = JSONArray()
            for ((q, op) in chunk) {
                frames.put(
                    JSONObject()
                        .put("record", "subscription")
                        .put("name", q)
                        .put("op_id", op)
                )
            }
            ws?.send(
                JSONObject()
                    .put("record", "msg_c2g_subscribe")
                    .put("queues_with_results", frames)
                    .toString()
            )
        }
        Thread {
            Thread.sleep(timeoutMs)
            finish()
        }.apply { isDaemon = true }.start()
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
        authed = false
    }

    private fun handle(text: String) {
        val m = try { JSONObject(text) } catch (e: Exception) { return }
        when (m.optString("record")) {
            "msg_g2c_result" -> {
                val op = m.optInt("op_id", -1)
                if (op == connectOp) {
                    val status = m.optInt("status", -1)
                    val err = if (m.has("error") && !m.isNull("error")) m.optString("error") else ""
                    if (status != 0 || err.isNotEmpty()) {
                        android.util.Log.e("IMVURadar", "imq: connect falhou ($status $err)")
                        onEvent(JSONObject().put("type", "imq_error").put("message", err.ifEmpty { "connect $status" }))
                    } else if (!authed) {
                        authed = true
                        connected = true
                        android.util.Log.i("IMVURadar", "imq: autenticado, abrindo floodgates")
                        ws?.send(JSONObject().put("record", "msg_c2g_open_floodgates").toString())
                        // o gateway precisa desse instante para registrar a 1a assinatura
                        Thread {
                            Thread.sleep(1200)
                            if (authed) resubscribe()
                        }.apply { isDaemon = true }.start()
                        onReady?.invoke()
                        onEvent(JSONObject().put("type", "imq_ready"))
                    }
                } else {
                    pending.remove(op)?.invoke(m.optInt("status", 1))
                }
            }
            "msg_g2c_send_message" -> {
                // so o mount de participantes traz usuario (roster/presenca)
                val mount = m.optString("mount", "")
                if (mount != "edge:participants") return
                val raw = try {
                    String(Base64.decode(m.optString("message", ""), Base64.DEFAULT))
                } catch (e: Exception) { return }
                val obj = try { JSONObject(raw) } catch (e: Exception) { return }
                val action = obj.optString("action")
                if (action.isEmpty()) return
                val objs = obj.optJSONArray("objects") ?: JSONArray()
                val queue = m.optString("queue", "")
                var room = if (queue.startsWith("inv:/chat/chat-")) {
                    queue.substringAfter("inv:/chat/chat-")
                } else ""
                val blob = buildString {
                    for (i in 0 until objs.length()) {
                        if (i > 0) append(' ')
                        append(objs.optString(i, ""))
                    }
                }
                if (room.isEmpty()) {
                    room = Regex("chat-(\\d+-\\d+)").find(blob)?.groupValues?.get(1) ?: ""
                }
                if (room.isEmpty()) return
                for (i in 0 until objs.length()) {
                    val url = objs.optString(i, "")
                    val userCid = Regex("/user-(\\d+)").find(url)?.groupValues?.get(1) ?: continue
                    onEvent(
                        JSONObject()
                            .put("type", "presence")
                            .put("action", action)
                            .put("room", room)
                            .put("cid", userCid)
                    )
                }
            }
        }
    }
}
