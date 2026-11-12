package dev.musealert.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.musealert.core.UsageSession

class Notifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "監視状態", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "使用超過警告", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun status(label: String?, session: UsageSession?): Notification =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(if (session != null) "${label}を使用中" else "スマホ使用状況を監視中")
            .setContentIntent(openApp())
            .setOngoing(true)
            .apply { if (session != null) chronometer(session) }
            .build()

    fun updateStatus(label: String?, session: UsageSession?) {
        manager.notify(ID_STATUS, status(label, session))
    }

    /** 再びポップアップさせるため、一度消してから出し直す。経過時間はシステムが数える。 */
    fun showAlert(label: String, session: UsageSession, elapsedMinutes: Long, thresholdMinutes: Int) {
        val over = elapsedMinutes - thresholdMinutes
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle("${label}を${elapsedMinutes}分間使用中")
            .setContentText(if (over <= 0) "そろそろ休憩しませんか？" else "目標の${thresholdMinutes}分を${over}分超過しています。")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp())
            .chronometer(session)
            .build()
        manager.cancel(ID_ALERT)
        manager.notify(ID_ALERT, notification)
    }

    fun clearAlert() = manager.cancel(ID_ALERT)

    private fun NotificationCompat.Builder.chronometer(session: UsageSession) =
        setWhen(session.startMillis).setShowWhen(true).setUsesChronometer(true)

    private fun openApp() = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ID_STATUS = 1
        const val ID_ALERT = 1001
        private const val CHANNEL_STATUS = "monitor_status"
        private const val CHANNEL_ALERT = "usage_alert"
    }
}
