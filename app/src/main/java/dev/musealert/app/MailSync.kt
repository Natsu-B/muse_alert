package dev.musealert.app

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.musealert.core.UsageRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * 専用 Gmail とのやりとりをまとめて行う。リクエストへの返信、前日分の日次レポート、手動送信。
 *
 * 電力を最も使うのは通信なので、自前のタイマーでは回さず WorkManager に任せる。
 * 画面 OFF の間は 15 分ごとの定期実行（OS が他の処理とまとめ、Doze 中は待たせる）、
 * 画面 ON になったときは前回から間隔が空いていればすぐ実行する。
 */
class MailSync(private val context: Context, private val zone: ZoneId = ZoneId.systemDefault()) {
    private val settings = Settings(context)
    private val reports = UsageReports(context, zone)

    suspend fun run(sendToday: Boolean) = lock.withLock {
        val config = settings.load()
        if (!settings.mailConfigured(config)) return@withLock
        val client = settings.mailClient(config)
        settings.lastMailCheckMillis = System.currentTimeMillis()
        try {
            val today = LocalDate.now(zone)
            var sent = false
            if (sendToday) sent = send(client, today, inReplyTo = null)

            val since = Date.from(today.minusDays(1).atStartOfDay(zone).toInstant())
            client.findRequests(UsageRequest.SUBJECT_TAG, since)
                .filterNot { settings.isProcessed(it.messageId) }
                .forEach { request ->
                    val date = UsageRequest.parseDate(request.subject, today)
                    if (date != null) sent = send(client, date, inReplyTo = request.messageId) || sent
                    // 送信に失敗しても処理済みにする。壊れたリクエストで送信を繰り返さないため。
                    settings.markProcessed(request.messageId)
                }
            sent = sendDigestIfDue(client, today) || sent
            if (!sent) settings.lastMailStatus = "確認 ${now()}: OK"
        } catch (e: Exception) {
            Log.e(TAG, "mail sync failed", e)
            settings.lastMailStatus = "エラー ${now()}: ${e.message}"
        }
    }

    /** 日付が変わり、前日から続くセッションも終わっていたら、前日分を送る。 */
    private fun sendDigestIfDue(client: MailClient, today: LocalDate): Boolean {
        val yesterday = today.minusDays(1)
        val last = settings.lastDigestDate ?: return false
        if (!last.isBefore(yesterday)) return false
        val ongoing = MonitorState.activeSession
        if (ongoing != null && SessionLog(context, zone).dateOf(ongoing) == yesterday) return false
        if (!send(client, yesterday, inReplyTo = null)) return false
        settings.lastDigestDate = yesterday
        return true
    }

    /** 送れたら true。失敗は画面に出す状態に残し、例外は投げない。 */
    private fun send(client: MailClient, date: LocalDate, inReplyTo: String?): Boolean = try {
        val partial = date == LocalDate.now(zone)
        val report = reports.build(date)
        client.sendToSelf(report.subject(partial), report.body(), inReplyTo)
        settings.lastMailStatus = "送信 ${now()}: ${report.subject(partial)}"
        true
    } catch (e: Exception) {
        Log.e(TAG, "send report failed", e)
        settings.lastMailStatus = "送信エラー ${now()}: ${e.message}"
        false
    }

    private fun now() = LocalTime.now(zone).withNano(0)

    companion object {
        private const val TAG = "MailSync"
        private const val PERIODIC = "mail-periodic"
        private const val NOW = "mail-now"
        private const val KEY_SEND_TODAY = "send_today"

        /** WorkManager の定期実行とすぐの実行が重ならないようにする。 */
        private val lock = Mutex()

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<MailWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }

        /** 画面 ON のときに呼ぶ。前回の確認から minIntervalMillis 経っていなければ何もしない。 */
        fun runIfDue(context: Context, minIntervalMillis: Long) {
            if (System.currentTimeMillis() - Settings(context).lastMailCheckMillis < minIntervalMillis) return
            runNow(context, sendToday = false)
        }

        fun runNow(context: Context, sendToday: Boolean) {
            val request = OneTimeWorkRequestBuilder<MailWorker>()
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_SEND_TODAY to sendToday))
                .build()
            // 手動送信は取りこぼさないよう追加し、自動の確認は 1 つにまとめる。
            val policy = if (sendToday) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, policy, request)
        }

        internal fun sendToday(params: WorkerParameters) = params.inputData.getBoolean(KEY_SEND_TODAY, false)
    }
}

class MailWorker(context: Context, private val params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        MailSync(applicationContext).run(MailSync.sendToday(params))
        return Result.success()
    }
}
