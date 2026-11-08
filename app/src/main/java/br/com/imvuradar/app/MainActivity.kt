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
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import br.com.imvuradar.app.core.Tracker
import br.com.imvuradar.app.update.Updater
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var pendingApk: File? = null

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
        // voltou da tela de "instalar apps desconhecidos": instala o APK baixado
        val apk = pendingApk
        if (apk != null && apk.exists() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls())
        ) {
            pendingApk = null
            installApk(apk)
        }
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
        pushToast("baixando atualização...")
        Updater.download(this, url) { file ->
            runOnUiThread {
                if (file == null) {
                    pushToast("falha no download ou assinatura inválida")
                    return@runOnUiThread
                }
                installApk(file)
            }
        }
    }

    private fun installApk(apk: File) {
        // Android exige permissao explicita para instalar APK fora da loja.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            pendingApk = apk
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
                pushToast("libere instalar apps desconhecidos e volte ao app")
            } catch (e: Exception) {
                pushToast("erro ao abrir configurações: ${e.message}")
            }
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            pushToast("erro ao abrir instalador: ${e.message}")
        }
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
        fun setRoomName(rid: String, name: String): String {
            Tracker.setRoomName(rid, name)
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
