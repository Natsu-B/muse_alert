package dev.musealert.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 再起動前に監視していたなら、監視を再開する。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Settings(context).monitoring) {
            UsageMonitorService.start(context)
        }
    }
}
