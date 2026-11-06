package br.com.imvuradar.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Guarda as credenciais IMVU cifradas (AES-GCM, chave no AndroidKeyStore).
 * Nada de credencial em texto puro no disco.
 */
class SecureStore(ctx: Context) {

    private val prefs: SharedPreferences =
        ctx.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)

    private val alias = "imvu_radar_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return kg.generateKey()
    }

    private fun enc(text: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val out = c.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + out, Base64.NO_WRAP)
    }

    private fun dec(b64: String): String? = try {
        val raw = Base64.decode(b64, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, 12)
        val body = raw.copyOfRange(12, raw.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(c.doFinal(body), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    fun saveCredentials(user: String, pass: String) {
        prefs.edit()
            .putString(Config.KEY_USER, enc(user))
            .putString(Config.KEY_PASS, enc(pass))
            .apply()
    }

    fun hasCredentials(): Boolean {
        val u = prefs.getString(Config.KEY_USER, null) ?: return false
        val p = prefs.getString(Config.KEY_PASS, null) ?: return false
        return dec(u) != null && dec(p) != null
    }

    fun credentials(): Pair<String, String>? {
        val u = prefs.getString(Config.KEY_USER, null) ?: return null
        val p = prefs.getString(Config.KEY_PASS, null) ?: return null
        val du = dec(u) ?: return null
        val dp = dec(p) ?: return null
        return Pair(du, dp)
    }

    fun clear() {
        prefs.edit().remove(Config.KEY_USER).remove(Config.KEY_PASS).apply()
    }

    fun lastUpdateCheck(): Long = prefs.getLong(Config.KEY_UPDATE, 0L)

    fun setLastUpdateCheck(ts: Long) = prefs.edit().putLong(Config.KEY_UPDATE, ts).apply()
}
