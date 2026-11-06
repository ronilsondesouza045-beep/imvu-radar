package br.com.imvuradar.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import br.com.imvuradar.app.core.Tracker
import br.com.imvuradar.app.service.TrackerService

class App : Application() {

    companion object {
        lateinit var ctx: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        ctx = this
        createChannel()
        Tracker.start()
        startServiceCompat()
    }

    private fun startServiceCompat() {
        try {
            val i = Intent(this, TrackerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        } catch (_: Exception) {
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(Config.notif_channelId) == null) {
                val ch = NotificationChannel(
                    Config.notif_channelId,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_MIN
                )
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        }
    }
}
