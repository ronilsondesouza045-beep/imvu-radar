package br.com.imvuradar.app.update

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import br.com.imvuradar.app.BuildConfig
import br.com.imvuradar.app.Config
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Auto-update: consulta o GitHub Releases (repo publico), compara versao,
 * baixa o APK e instala (FileProvider + REQUEST_INSTALL_PACKAGES).
 */
object Updater {

    /**
     * Impressao digital SHA-256 do certificado de assinatura.
     * O app so instala atualizacao assinada por ESSA chave - se alguem
     * publicar um APK falso na release, a verificacao barra a instalacao.
     */
    private const val CERT_SHA256 =
        "6FAC2AD37A155EE5D0806C6084CB4C911FA81CA1773BF1BFA6E1DC97863D6D23"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val pool = Executors.newSingleThreadExecutor()

    /** confere se o APK baixado foi assinado pela nossa chave */
    fun verifySignature(ctx: Context, apk: File): Boolean {
        return try {
            val pm = ctx.packageManager
            val info = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: return false
            val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val s = info.signingInfo ?: return false
                if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
            if (sigs == null || sigs.isEmpty()) return false
            val expected = CERT_SHA256.uppercase().replace(":", "")
            sigs.any { sig ->
                val md = MessageDigest.getInstance("SHA-256")
                val hex = md.digest(sig.toByteArray()).joinToString("") { "%02X".format(it) }
                hex == expected
            }
        } catch (e: Exception) {
            false
        }
    }

    fun check(ctx: Context, onResult: (JSONObject) -> Unit) {
        pool.submit {
            val out = JSONObject()
            try {
                val req = Request.Builder().url(Config.GITHUB_LATEST).header("User-Agent", Config.UA).build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        out.put("ok", false).put("error", "HTTP ${resp.code}")
                    } else {
                        val rel = JSONObject(body)
                        out.put("ok", true)
                        out.put("tag", rel.optString("tag_name"))
                        out.put("notes", rel.optString("body").take(400))
                        var best: JSONObject? = null
                        var bestCode = BuildConfig.VERSION_CODE
                        val assets = rel.optJSONArray("assets") ?: org.json.JSONArray()
                        for (i in 0 until assets.length()) {
                            val a = assets.optJSONObject(i) ?: continue
                            val name = a.optString("name")
                            if (!name.endsWith(".apk")) continue
                            val code = Regex("-(\\d+)\\.apk").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            if (code > bestCode) {
                                bestCode = code
                                best = a
                            }
                        }
                        if (best != null) {
                            out.put("update", true)
                            out.put("versionCode", bestCode)
                            out.put("url", best.optString("browser_download_url"))
                            out.put("size", best.optLong("size"))
                        } else {
                            out.put("update", false)
                            out.put("current", BuildConfig.VERSION_CODE)
                        }
                    }
                }
            } catch (e: Exception) {
                out.put("ok", false).put("error", e.message ?: "erro")
            }
            onResult(out)
        }
    }

    /**
     * Baixa e valida a assinatura. NAO inicia activity daqui: o instalador
     * precisa ser aberto na UI thread da Activity (senao o Android bloqueia
     * o background activity launch e nada aparece).
     */
    fun download(ctx: Context, url: String, onDone: (File?) -> Unit) {
        pool.submit {
            try {
                val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "updates").apply { mkdirs() }
                val out = File(dir, "update.apk")
                if (out.exists()) out.delete()
                val req = Request.Builder().url(url).header("User-Agent", Config.UA).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        onDone(null)
                        return@submit
                    }
                    val body = resp.body ?: throw IllegalStateException("sem corpo")
                    body.byteStream().use { input ->
                        out.outputStream().use { o -> input.copyTo(o) }
                    }
                }
                if (!verifySignature(ctx, out)) {
                    out.delete()
                    onDone(null)
                    return@submit
                }
                onDone(out)
            } catch (e: Exception) {
                onDone(null)
            }
        }
    }
}
