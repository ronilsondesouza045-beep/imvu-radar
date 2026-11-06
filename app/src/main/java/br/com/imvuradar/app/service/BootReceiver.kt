package br.com.imvuradar.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TrackerService.start(context)
        TrackerService.schedule(context)
    }
}
