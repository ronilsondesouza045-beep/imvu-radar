package br.com.imvuradar.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import br.com.imvuradar.app.App
import br.com.imvuradar.app.Config
import br.com.imvuradar.app.R
import br.com.imvuradar.app.core.Tracker

/**
 * Servico em primeiro plano: mantem o IMQ vivo.
 * - START_STICKY: o sistema reinicia se matar
 * - watchdog por AlarmManager: religa a cada 2 min se o processo morrer
 * - onTaskRemoved: reagenda (nao morre quando tu fecha o app)
 */
class TrackerService : Service() {

    companion object {
        private const val WATCHDOG_MS = 120_000L

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

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        Tracker.start()
        br.com.imvuradar.app.core.SiteSync.start()
        schedule(this)
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
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        schedule(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
