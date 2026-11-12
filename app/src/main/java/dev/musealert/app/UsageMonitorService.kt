package dev.musealert.app

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.musealert.core.SessionTracker
import dev.musealert.core.UsageEvent
import dev.musealert.core.UsageSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 監視の本体。電力を抑えるため、次のように動く。
 *
 * - 画面 OFF の間は何もしない。OFF になった時点でセッションを閉じ、ループを止める。
 * - 画面 ON の間は、通常は 60 秒ごとに使用イベントを読む。開始時刻はイベントの時刻で決まるので、
 *   気づくのが遅れても計測はずれない。次の警告時刻が近ければ、その時刻ちょうどに起きる。
 * - メールの確認は WorkManager に任せる (MailSync)。
 */
class UsageMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** トラッカーには 1 本のスレッドからしか触らない。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val zone: ZoneId = ZoneId.systemDefault()

    private lateinit var settings: Settings
    private lateinit var config: Config
    private lateinit var notifications: Notifications
    private lateinit var events: UsageEventSource
    private lateinit var log: SessionLog
    private lateinit var labels: AppLabels
    private lateinit var tracker: SessionTracker

    private var cursorMillis = 0L
    private var shownSession: UsageSession? = null
    private var pollJob: Job? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> scope.launch(serial) { onScreenOff() }
                Intent.ACTION_SCREEN_ON -> scope.launch(serial) { onScreenOn() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        config = settings.load()
        notifications = Notifications(this)
        events = UsageEventSource(this)
        log = SessionLog(this, zone)
        labels = AppLabels(this)
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
        if (cursorMillis == 0L) start()
        return START_STICKY
    }

    private fun start() {
        settings.monitoring = true
        // 再起動直後でも継続中のセッションを拾えるよう、少し前からイベントを読み直す。
        cursorMillis = System.currentTimeMillis() - LOOKBACK_MILLIS
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        MailSync.schedulePeriodic(this)
        scope.launch(serial) {
            if (getSystemService(PowerManager::class.java).isInteractive) onScreenOn() else pollUsage(System.currentTimeMillis())
        }
    }

    private fun onScreenOn() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch(serial) {
            while (isActive) {
                val now = System.currentTimeMillis()
                val wakeAt = runCatching { pollUsage(now) }
                    .onFailure { Log.e(TAG, "usage poll failed", it) }
                    .getOrDefault(now + IDLE_POLL_MILLIS)
                delay((wakeAt - System.currentTimeMillis()).coerceAtLeast(MIN_DELAY_MILLIS))
            }
        }
        MailSync.runIfDue(this, config.mailCheckMinutes * 60_000L)
    }

    private fun onScreenOff() {
        pollJob?.cancel()
        pollJob = null
        val now = System.currentTimeMillis()
        pollUsage(now)
        // OS 側の SCREEN_NON_INTERACTIVE が記録される前に止まることがあるので、自分でも閉じる。
        // 後から届く本物のイベントは、閉じたあとなので何も起こさない。
        tracker.onEvent(UsageEvent(now, UsageEvent.Type.SCREEN_OFF))
        afterEvents()
    }

    /** イベントを読んで判定する。次に起きるべき時刻を返す。 */
    private fun pollUsage(now: Long): Long {
        events.query(cursorMillis, now).forEach(tracker::onEvent)
        cursorMillis = now
        tracker.expire(now)
        afterEvents()

        val session = tracker.active ?: return now + IDLE_POLL_MILLIS
        val policy = config.policyFor(session.packageName) ?: return now + IDLE_POLL_MILLIS
        if (!tracker.isInForeground()) return now + IDLE_POLL_MILLIS

        val elapsed = session.durationMillis(now)
        if (policy.shouldAlert(elapsed, tracker.alertsConsumed, LocalTime.now(zone))) {
            tracker.recordAlert(policy.alertsDue(elapsed))
            MonitorState.activeSession = tracker.active
            val minutes = config.target(session.packageName)!!.thresholdMinutes
            notifications.showAlert(labels.label(session.packageName), session, elapsed / 60_000, minutes)
        }
        val nextAlert = policy.nextAlertAtMillis(session.startMillis, tracker.alertsConsumed)
        return minOf(now + IDLE_POLL_MILLIS, nextAlert ?: Long.MAX_VALUE)
    }

    /** 終わったセッションを記録し、通知の表示を今の状態に合わせる。 */
    private fun afterEvents() {
        tracker.drainCompleted().forEach(log::append)
        val session = tracker.active
        MonitorState.activeSession = session
        if (session?.startMillis != shownSession?.startMillis || session?.packageName != shownSession?.packageName) {
            if (session == null) notifications.clearAlert()
            notifications.updateStatus(session?.let { labels.label(it.packageName) }, session)
            shownSession = session
        }
    }

    override fun onDestroy() {
        if (cursorMillis != 0L) {
            unregisterReceiver(screenReceiver)
            MailSync.cancelPeriodic(this)
        }
        // 継続中のセッションは、止めた時刻で終わったことにして残す。再起動後に同じセッションを拾えば延長される。
        tracker.active?.let { log.append(it.copy(endMillis = System.currentTimeMillis())) }
        MonitorState.activeSession = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "UsageMonitor"
        private const val LOOKBACK_MILLIS = 2 * 60 * 60_000L
        private const val IDLE_POLL_MILLIS = 60_000L
        private const val MIN_DELAY_MILLIS = 1_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, UsageMonitorService::class.java))
        }

        fun stop(context: Context) {
            Settings(context).monitoring = false
            context.stopService(Intent(context, UsageMonitorService::class.java))
        }
    }
}
