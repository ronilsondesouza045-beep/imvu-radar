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
        fun where(nick: String): String {
            Tracker.where(nick)
            return "queued"
        }

        @JavascriptInterface
        fun friends(): String {
            Tracker.friends()
            return "queued"
        }

        @JavascriptInterface
        fun room(rid: String): String {
            Tracker.room(rid)
            return "queued"
        }

        @JavascriptInterface
        fun enter(rid: String): String {
            Tracker.enter(rid)
            return "queued"
        }

        @JavascriptInterface
        fun leave(rid: String): String {
            Tracker.leave(rid)
            return "queued"
        }

        @JavascriptInterface
        fun watchTarget(nick: String): String {
            Tracker.watchTarget(nick)
            return "queued"
        }

        @JavascriptInterface
        fun lastSeen(cid: String): String = Tracker.lastSeen(cid).toString()

        @JavascriptInterface
        fun scan(target: String, owners: String, max: String): String {
            Tracker.scan(target, owners.split(",").map { it.trim() }.filter { it.isNotEmpty() }, max.toIntOrNull() ?: 250)
            return "queued"
        }

        @JavascriptInterface
        fun deepScan(target: String, people: String, rooms: String): String {
            Tracker.deepScan(target, people.toIntOrNull() ?: 40, rooms.toIntOrNull() ?: 40)
            return "queued"
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
