package br.com.imvuradar.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import br.com.imvuradar.app.core.LocalState
import org.json.JSONObject

/**
 * Sala dentro do app: WebView com o client do IMVU + a ponte injetada.
 * Tu loga uma vez (a sessao fica), e o app mostra o roster ao vivo.
 * Como a sessao e a do navegador, funciona ate em sala VIP / "My Room"
 * (o proprio dono da sessao e autorizado).
 */
class RoomWebActivity : AppCompatActivity() {

    private var web: WebView? = null
    private var status: TextView? = null
    private var rosterView: TextView? = null
    private var injected = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val roomId = intent.getStringExtra("roomId") ?: ""
        if (roomId.isNotEmpty()) LocalState.lastRoomId = roomId

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF05070C.toInt())
        }

        val header = TextView(this).apply {
            text = "IMVU · sala $roomId  (faça login se aparecer a tela do IMVU)"
            setTextColor(0xFFE9EEFB.toInt())
            textSize = 13f
            setPadding(24, 18, 24, 18)
        }
        root.addView(header)

        status = TextView(this).apply {
            text = "abrindo…"
            setTextColor(0xFF8EA0C4.toInt())
            textSize = 12f
            setPadding(24, 0, 24, 8)
        }
        root.addView(status)

        rosterView = TextView(this).apply {
            setTextColor(0xFFE9EEFB.toInt())
            textSize = 12.5f
            setPadding(24, 4, 24, 24)
        }
        root.addView(rosterView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val wv = WebView(this)
        web = wv
        root.addView(wv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = Config.UA
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.addJavascriptInterface(WebBridge(), "AndroidBridge")

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                injected = false
                injectBridge(view)
            }

            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? = null
        }

        wv.loadUrl("https://pt.imvu.com/next/chat/room-$roomId/")
    }

    private fun injectBridge(view: WebView) {
        if (injected) return
        injected = true
        try {
            val js = assets.open("bridge/bridge.js").bufferedReader().use { it.readText() }
            view.evaluateJavascript(js, null)
        } catch (e: Exception) {
            runOnUiThread { status?.text = "falha ao injetar: ${e.message}" }
        }
    }

    inner class WebBridge {
        @JavascriptInterface
        fun onRoster(json: String) {
            runOnUiThread {
                try {
                    val o = JSONObject(json)
                    val rid = o.optString("roomId")
                    val name = o.optString("name")
                    val priv = o.optString("privacy")
                    val occ = o.optInt("occupancy", -1)
                    val cap = o.optInt("capacity", -1)
                    val cnt = o.optInt("count", 0)
                    val people = o.optJSONArray("people")
                    val sb = StringBuilder()
                    sb.append("SALA ").append(rid).append(" · ")
                    if (name.isNotEmpty()) sb.append(name).append(" · ")
                    sb.append(if (priv == "private") "PRIVADA" else "PUBLICA")
                    if (occ >= 0) sb.append(" · ").append(occ).append("/").append(cap)
                    sb.append(" · ").append(cnt).append(" pessoa(s)")
                    status?.text = sb.toString()
                    val names = StringBuilder()
                    for (i in 0 until (people?.length() ?: 0)) {
                        val p = people!!.getJSONObject(i)
                        names.append("· ").append(p.optString("display_name", p.optString("username")))
                            .append("  (").append(p.optString("cid")).append(")\n")
                    }
                    if (names.isEmpty()) names.append("(sem gente visivel)")
                    rosterView?.text = names.toString()
                } catch (e: Exception) {
                    status?.text = "erro: ${e.message}"
                }
            }
        }
    }

    override fun onDestroy() {
        web?.destroy()
        super.onDestroy()
    }
}
