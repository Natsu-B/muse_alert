package dev.musealert.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.musealert.core.DailyReport
import dev.musealert.core.SessionTracker
import dev.musealert.core.UsageRequest
import dev.musealert.core.UsageSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date

/**
 * 監視の本体。画面 ON 中は 10 秒ごとに使用イベントを読み、連続使用を判定して通知する。
 * 専用 Gmail が設定されていれば、Muse からの履歴リクエストへの返信と、前日分の日次レポートも送る。
 */
class UsageMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mailMutex = Mutex()
    private val zone: ZoneId = ZoneId.systemDefault()

    private lateinit var settings: Settings
    private lateinit var config: Config
    private lateinit var notifications: Notifications
    private lateinit var events: UsageEventSource
    private lateinit var log: SessionLog
    private lateinit var tracker: SessionTracker

    /** メール処理 (IO スレッド) から読むための、継続中セッションの写し。 */
    @Volatile
    private var activeSnapshot: UsageSession? = null

    private var cursorMillis = 0L
    private var nextMailCheckMillis = 0L
    private var shownSession: UsageSession? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        config = settings.load()
        notifications = Notifications(this)
        events = UsageEventSource(this)
        log = SessionLog(this, zone)
        tracker = SessionTracker(config.targetPackages, config.graceSeconds * 1_000L)
        if (settings.lastDigestDate == null) settings.lastDigestDate = LocalDate.now(zone).minusDays(1)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            Notifications.ID_STATUS,
            notifications.status(null, null),
            // specialUse は Android 14 から。それより前は種別を指定しない。
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        when (intent?.action) {
            ACTION_SEND_TODAY -> scope.launch(Dispatchers.IO) {
                mailMutex.withLock { sendReport(LocalDate.now(zone), inReplyTo = null) }
                // 監視していないときに送信のためだけに起動した場合は、送ったら止まる。
                if (!settings.monitoring) stopSelf()
            }
            else -> if (cursorMillis == 0L) start()
        }
        return START_STICKY
    }

    private fun start() {
        settings.monitoring = true
        val now = System.currentTimeMillis()
        // 再起動直後でも継続中のセッションを拾えるよう、少し前からイベントを読み直す。
        cursorMillis = now - LOOKBACK_MILLIS
        scope.launch {
            while (isActive) {
                val loopStart = System.currentTimeMillis()
                runCatching { pollUsage(loopStart) }.onFailure { Log.e(TAG, "usage poll failed", it) }
                if (settings.mailConfigured(config) && loopStart >= nextMailCheckMillis) {
                    nextMailCheckMillis = loopStart + config.mailCheckMinutes * 60_000L
                    launch(Dispatchers.IO) { checkMail() }
                }
                val interactive = getSystemService(PowerManager::class.java).isInteractive
                delay(if (interactive) POLL_INTERACTIVE_MILLIS else POLL_IDLE_MILLIS)
            }
        }
    }

    private fun pollUsage(now: Long) {
        events.query(cursorMillis, now).forEach(tracker::onEvent)
        cursorMillis = now
        tracker.expire(now)

        tracker.drainCompleted().forEach(log::append)

        val session = tracker.active
        activeSnapshot = session
        if (session?.startMillis != shownSession?.startMillis || session?.packageName != shownSession?.packageName) {
            if (session == null) notifications.clearAlert()
            notifications.updateStatus(session?.let { label(it.packageName) }, session)
            shownSession = session
        }

        if (session != null && tracker.isInForeground()) {
            val elapsed = session.durationMillis(now)
            val localTime = LocalTime.now(zone)
            if (config.policy.shouldAlert(elapsed, tracker.alertsConsumed, localTime)) {
                tracker.recordAlert(config.policy.alertsDue(elapsed))
                notifications.showAlert(label(session.packageName), session, elapsed / 60_000, config.thresholdMinutes)
            }
        }
    }

    private suspend fun checkMail() = mailMutex.withLock {
        val client = MailClient(config.gmailAddress, settings.appPassword)
        try {
            val today = LocalDate.now(zone)
            val since = Date.from(today.minusDays(1).atStartOfDay(zone).toInstant())
            var sent = false
            client.findRequests(UsageRequest.SUBJECT_TAG, since)
                .filterNot { settings.isProcessed(it.messageId) }
                .forEach { request ->
                    val date = UsageRequest.parseDate(request.subject, today)
                    if (date != null) sent = sendReport(date, inReplyTo = request.messageId, client) || sent
                    // 送信に失敗しても処理済みにする。壊れたリクエストで送信を繰り返さないため。
                    settings.markProcessed(request.messageId)
                }
            sent = sendDigestIfDue(client, today) || sent
            if (!sent) settings.lastMailStatus = "確認 ${LocalTime.now(zone).withNano(0)}: OK"
        } catch (e: Exception) {
            Log.e(TAG, "mail check failed", e)
            settings.lastMailStatus = "エラー ${LocalTime.now(zone).withNano(0)}: ${e.message}"
        }
    }

    /** 日付が変わり、前日から続くセッションも終わっていたら、前日分を送る。 */
    private fun sendDigestIfDue(client: MailClient, today: LocalDate): Boolean {
        val yesterday = today.minusDays(1)
        val last = settings.lastDigestDate ?: return false
        if (!last.isBefore(yesterday)) return false
        val ongoing = activeSnapshot
        if (ongoing != null && log.dateOf(ongoing) == yesterday) return false
        if (!sendReport(yesterday, inReplyTo = null, client)) return false
        settings.lastDigestDate = yesterday
        return true
    }

    /** 送れたら true。失敗は画面に出す状態に残し、例外は投げない。 */
    private fun sendReport(
        date: LocalDate,
        inReplyTo: String?,
        client: MailClient = MailClient(config.gmailAddress, settings.appPassword),
    ): Boolean {
        return try {
            val today = LocalDate.now(zone)
            val sessions = log.sessionsOn(date) + listOfNotNull(activeSnapshot?.takeIf { log.dateOf(it) == date })
            val labels = sessions.map { it.packageName }.distinct().associateWith(::label)
            val report = DailyReport(date, sessions, labels, Instant.now(), zone)
            client.sendToSelf(report.subject(partial = date == today), report.body(), inReplyTo)
            settings.lastMailStatus = "送信 ${LocalTime.now(zone).withNano(0)}: ${report.subject(date == today)}"
            true
        } catch (e: Exception) {
            Log.e(TAG, "send report failed", e)
            settings.lastMailStatus = "送信エラー ${LocalTime.now(zone).withNano(0)}: ${e.message}"
            false
        }
    }

    private fun label(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    override fun onDestroy() {
        // 継続中のセッションは、止めた時刻で終わったことにして残す。再起動後に同じセッションを拾えば延長される。
        tracker.active?.let { log.append(it.copy(endMillis = System.currentTimeMillis())) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "UsageMonitor"
        private const val ACTION_SEND_TODAY = "dev.musealert.app.SEND_TODAY"
        private const val LOOKBACK_MILLIS = 2 * 60 * 60_000L
        private const val POLL_INTERACTIVE_MILLIS = 10_000L
        private const val POLL_IDLE_MILLIS = 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, UsageMonitorService::class.java))
        }

        fun stop(context: Context) {
            Settings(context).monitoring = false
            context.stopService(Intent(context, UsageMonitorService::class.java))
        }

        fun sendToday(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, UsageMonitorService::class.java).setAction(ACTION_SEND_TODAY),
            )
        }
    }
}
