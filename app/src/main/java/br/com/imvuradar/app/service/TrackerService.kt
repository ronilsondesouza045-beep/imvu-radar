package br.com.imvuradar.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import br.com.imvuradar.app.App
import br.com.imvuradar.app.Config
import br.com.imvuradar.app.R
import br.com.imvuradar.app.core.Tracker
import org.json.JSONObject
import java.util.Locale

/**
 * Servico em primeiro plano: mantem o IMQ vivo.
 * - START_STICKY: o sistema reinicia se matar
 * - watchdog por AlarmManager: religa a cada 2 min se o processo morrer
 * - onTaskRemoved: reagenda (nao morre quando tu fecha o app)
 * - TTS nativo (offline) para anunciar entrada/saida de sala em pt-BR
 */
class TrackerService : Service() {

    companion object {
        private const val WATCHDOG_MS = 120_000L
        private const val TAG = "IMVURadar"

        // voz: ajuste fino fica aqui
        private const val SPEECH_RATE = 1.0f
        private const val SPEECH_PITCH = 1.0f
        private const val SPEECH_DEBOUNCE_MS = 500L
        private const val PREF_SOM = "som_ativo"

        private val LOCALE_BR = Locale("pt", "BR")

        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, TrackerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (_: Exception) {
            }
        }

        fun schedule(ctx: Context) {
            try {
                val am = ctx.getSystemService(AlarmManager::class.java)
                val pi = PendingIntent.getBroadcast(
                    ctx, 991,
                    Intent(ctx, BootReceiver::class.java).setAction("com.imvuradar.app.WATCHDOG"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, System.currentTimeMillis() + WATCHDOG_MS, pi)
                } else {
                    am.set(AlarmManager.ELAPSED_REALTIME, System.currentTimeMillis() + WATCHDOG_MS, pi)
                }
            } catch (_: Exception) {
            }
        }
    }

    private var tts: TextToSpeech? = null

    @Volatile private var ttsReady = false
    @Volatile private var falaPtBr = false

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var pendente: String? = null
    private val debounceTask = Runnable {
        pendente?.let { texto ->
            pendente = null
            falar(texto)
        }
    }

    /** o Tracker chama este hook; a fala roda aqui no service */
    private val voz: (String, Boolean) -> Unit = { texto, debounce ->
        if (debounce) falarDebounced(texto) else falar(texto)
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        Tracker.start()
        br.com.imvuradar.app.core.SiteSync.start()
        schedule(this)
        iniciarTts()
        Tracker.speaker = voz
    }

    override fun onDestroy() {
        Tracker.speaker = null
        main.removeCallbacks(debounceTask)
        pendente = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ttsReady = false
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notif = NotificationCompat.Builder(this, Config.notif_channelId)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText("monitoramento ativo em segundo plano")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(Config.NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(Config.NOTIF_ID, notif)
            }
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        Tracker.start()
        br.com.imvuradar.app.core.SiteSync.start()
        schedule(this)
        Tracker.speaker = voz
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        schedule(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------- TTS (nativo, offline) ----------------

    private fun iniciarTts() {
        try {
            tts = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    configurarTts()
                } else {
                    Log.e(TAG, "[tts] falha ao inicializar o TextToSpeech (status $status)")
                    avisoVoz()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[tts] erro ao criar TextToSpeech: ${e.message}")
            avisoVoz()
        }
    }

    private fun configurarTts() {
        val t = tts ?: return
        val disp = try {
            t.isLanguageAvailable(LOCALE_BR)
        } catch (_: Exception) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
        falaPtBr = disp >= TextToSpeech.LANG_AVAILABLE
        if (falaPtBr) {
            val r = try {
                t.setLanguage(LOCALE_BR)
            } catch (_: Exception) {
                TextToSpeech.LANG_NOT_SUPPORTED
            }
            falaPtBr = r >= TextToSpeech.LANG_AVAILABLE
        }
        if (!falaPtBr) {
            Log.e(TAG, "[tts] pt-BR indisponivel (retorno=$disp); fallback Locale.getDefault()")
            try {
                t.setLanguage(Locale.getDefault())
            } catch (_: Exception) {
            }
            avisoVoz()
        }
        try {
            t.setSpeechRate(SPEECH_RATE)
            t.setPitch(SPEECH_PITCH)
        } catch (_: Exception) {
        }
        ttsReady = true
        Log.i(TAG, "[tts] pronto (pt-BR=$falaPtBr)")
    }

    /** aviso na tela inicial: log vira toast no painel; Toast cobre boot sem UI aberta */
    private fun avisoVoz() {
        val msg = "Para ouvir os alertas, instale o pacote de voz do português em " +
            "Configurações > Idioma > Saída de texto para fala"
        try {
            Tracker.emit(JSONObject().put("type", "log").put("msg", msg))
        } catch (_: Exception) {
        }
        try {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
        }
    }

    private fun somAtivo(): Boolean = try {
        getSharedPreferences(Config.PREFS, MODE_PRIVATE).getBoolean(PREF_SOM, true)
    } catch (_: Exception) {
        true
    }

    /** fala imediata; QUEUE_FLUSH sempre anuncia o estado ATUAL */
    private fun falar(texto: String) {
        if (texto.isBlank()) return
        if (!somAtivo()) return
        if (!ttsReady) {
            Log.w(TAG, "[tts] ainda nao pronto; ignorando: $texto")
            return
        }
        try {
            tts?.speak(
                texto, TextToSpeech.QUEUE_FLUSH, null,
                "announce_${System.currentTimeMillis()}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "[tts] erro ao falar: ${e.message}")
        }
    }

    /** rajada de entradas/saidas: fala so o estado final depois de 500ms quieto */
    private fun falarDebounced(texto: String) {
        if (texto.isBlank()) return
        if (!somAtivo()) return
        pendente = texto
        main.removeCallbacks(debounceTask)
        main.postDelayed(debounceTask, SPEECH_DEBOUNCE_MS)
    }
}
