package br.com.imvuradar.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import br.com.imvuradar.app.core.Tracker
import br.com.imvuradar.app.update.Updater
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var pendingUpdateUrl: String? = null

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        setContentView(web)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            userAgentString = Config.UA
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(req.url)

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean = true
        }
        web.addJavascriptInterface(Bridge(), "Radar")

        Tracker.listener = { evt ->
            runOnUiThread {
                try {
                    web.evaluateJavascript("window.__radarEvent && window.__radarEvent(${evt})", null)
                } catch (_: Exception) {
                }
            }
        }

        web.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")

        askNotifications()
        askBatteryExemption()

        // auto-update: consulta a release mais nova no launch
        Updater.check(this) { res ->
            runOnUiThread {
                try {
                    web.evaluateJavascript(
                        "window.__radarEvent && window.__radarEvent({type:'update',data:$res})",
                        null
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Tracker.start()
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun askBatteryExemption() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val dg = getSystemService(android.os.PowerManager::class.java)
                if (dg != null && !dg.isIgnoringBatteryOptimizations(packageName)) {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    fun doUpdate(url: String) {
        pendingUpdateUrl = url
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }
        } catch (_: Exception) {
        }
        Updater.downloadAndInstall(this, url) { msg -> runOnUiThread { pushToast(msg) } }
    }

    private fun pushToast(msg: String) {
        try {
            web.evaluateJavascript("window.__radarEvent && window.__radarEvent({type:'log',msg:'${msg.replace("'", "")}'})", null)
        } catch (_: Exception) {
        }
    }

    inner class Bridge {

        @JavascriptInterface
        fun where(nick: String): String {
            Tracker.where(nick)
            return "queued"
        }

        @JavascriptInterface
        fun track(nick: String): String {
            Tracker.track(nick)
            return "queued"
        }

        @JavascriptInterface
        fun stopTrack(): String {
            Tracker.stopTrack()
            return "ok"
        }

        @JavascriptInterface
        fun status(): String = Tracker.status().toString()

        /** render do IMVU com sessao -> data URI (imagens do imvu pedem login) */
        @JavascriptInterface
        fun fetchImage(url: String, cb: String) {
            if (url.isBlank()) return
            Thread {
                val data = try {
                    val bytes = Tracker.api.authGetBytes(url)
                    if (bytes == null) "" else "data:image/png;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                } catch (e: Exception) {
                    ""
                }
                runOnUiThread {
                    try {
                        web.evaluateJavascript(
                            "window.__radarImage && window.__radarImage('${cb.replace("'", "")}','${data}')",
                            null
                        )
                    } catch (_: Exception) {
                    }
                }
            }.start()
        }

        @JavascriptInterface
        fun hasCredentials(): String = Tracker.hasCredentials().toString()

        @JavascriptInterface
        fun setCredentials(user: String, pass: String): String {
            Tracker.setCredentials(user, pass)
            return "ok"
        }

        @JavascriptInterface
        fun checkUpdate(): String {
            Updater.check(this@MainActivity) { res ->
                runOnUiThread {
                    web.evaluateJavascript("window.__radarEvent && window.__radarEvent({type:'update',data:${res}})", null)
                }
            }
            return "checking"
        }

        @JavascriptInterface
        fun installUpdate(url: String): String {
            doUpdate(url)
            return "ok"
        }
    }
}
