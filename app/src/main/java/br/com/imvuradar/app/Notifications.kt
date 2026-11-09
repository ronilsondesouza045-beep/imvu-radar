package br.com.imvuradar.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Notificacoes de sala (entrou/saiu/alvo ja estava) no canal de alerta. */
object Notifications {

    const val CHANNEL = "radar_alerts"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(
                    CHANNEL,
                    "Alertas de sala",
                    NotificationManager.IMPORTANCE_HIGH
                )
                ch.enableVibration(true)
                nm.createNotificationChannel(ch)
            }
        }
    }

    fun alert(ctx: Context, title: String, text: String, viewUrl: String? = null) {
        try {
            ensureChannel(ctx)
            // com url, o toque abre a sala direto no navegador; sem url, abre o app
            val pi = if (!viewUrl.isNullOrBlank()) {
                val view = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(viewUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                PendingIntent.getActivity(
                    ctx, viewUrl.hashCode(), view,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                val intent = Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                PendingIntent.getActivity(
                    ctx, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            NotificationManagerCompat.from(ctx)
                .notify(System.currentTimeMillis().toInt(), n)
        } catch (_: Exception) {
        }
    }
}
