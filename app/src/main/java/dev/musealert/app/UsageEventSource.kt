package dev.musealert.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import dev.musealert.core.UsageEvent

/** UsageStatsManager からイベントを読み、core の UsageEvent に変換する。 */
class UsageEventSource(private val context: Context) {
    private val usageStats = context.getSystemService(UsageStatsManager::class.java)

    fun query(beginMillis: Long, endMillis: Long): List<UsageEvent> {
        val events = usageStats.queryEvents(beginMillis, endMillis) ?: return emptyList()
        val event = UsageEvents.Event()
        val result = mutableListOf<UsageEvent>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageEvent.Type.RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> UsageEvent.Type.PAUSED
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageEvent.Type.SCREEN_OFF
                UsageEvents.Event.DEVICE_SHUTDOWN -> UsageEvent.Type.SCREEN_OFF
                UsageEvents.Event.KEYGUARD_SHOWN -> UsageEvent.Type.KEYGUARD_SHOWN
                else -> continue
            }
            result += UsageEvent(event.timeStamp, type, event.packageName ?: "")
        }
        return result
    }

    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
