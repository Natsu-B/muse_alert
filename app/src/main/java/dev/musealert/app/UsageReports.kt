package dev.musealert.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.musealert.core.DailyReport
import dev.musealert.core.UsageHistory
import dev.musealert.core.UsageSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 監視サービスとメール処理で共有する、継続中セッションの写し。 */
object MonitorState {
    @Volatile
    var activeSession: UsageSession? = null
}

/**
 * レポートを組み立てる。OS に残っているその日のイベントから全アプリの使用を組み立て直し、
 * 監視中に記録した分 (警告回数を持つ) で置き換える。重い処理はレポートを作るときだけ行う。
 */
class UsageReports(private val context: Context, private val zone: ZoneId = ZoneId.systemDefault()) {
    private val settings = Settings(context)
    private val log = SessionLog(context, zone)
    private val events = UsageEventSource(context)
    private val labels = AppLabels(context)

    fun build(date: LocalDate, now: Instant = Instant.now()): DailyReport {
        val config = settings.load()
        val nowMillis = now.toEpochMilli()
        val logged = log.sessionsOn(date) + listOfNotNull(MonitorState.activeSession?.takeIf { log.dateOf(it) == date })

        // 前日の夜から続くセッションの始まりを拾えるよう、少し前から読む。セッションは開始日で振り分ける。
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = minOf(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), nowMillis)
        val excluded = labels.launchers() + SYSTEM_UI
        val reconstructed = UsageHistory.reconstruct(
            events.query(dayStart - LOOKBEFORE_MILLIS, dayEnd),
            gracePeriodMillis = config.graceSeconds * 1_000L,
            nowMillis = dayEnd,
            include = { it !in excluded },
        ).filter { log.dateOf(it) == date }
            // 過去の日付で最後まで続いていたものは、その日の終わりで切る。
            .map { if (it.endMillis == null && dayEnd < nowMillis) it.copy(endMillis = dayEnd) else it }

        val sessions = UsageHistory.merge(logged, reconstructed, nowMillis)
        val names = sessions.map { it.packageName }.distinct().associateWith(labels::label)
        return DailyReport(date, sessions, names, now, zone, targets = config.targetPackages)
    }

    private companion object {
        const val LOOKBEFORE_MILLIS = 6 * 60 * 60_000L
        const val SYSTEM_UI = "com.android.systemui"
    }
}

/** アプリの表示名と、ホームアプリ (ランチャー) の一覧。 */
class AppLabels(private val context: Context) {
    private companion object {
        const val SETTINGS = "com.android.settings"
    }

    private val pm = context.packageManager

    fun label(pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    /**
     * ランチャーは「アプリを使った」に数えない。
     *
     * HOME を受けるアプリをすべて除くと、起動直後用の予備のホーム (FallbackHome) を持つ
     * 設定アプリまで消えてしまう。そのため既定のホームアプリだけを除く。既定が決まっていなければ
     * (選択ダイアログが出る状態)、候補から設定アプリを除いたものを使う。
     */
    fun launchers(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val default = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        if (default != null && default != "android") return setOf(default)
        return pm.queryIntentActivities(home, 0).map { it.activityInfo.packageName }.toSet() - SETTINGS
    }

    /** ランチャーに出ているアプリ。対象アプリを選ぶ画面で使う。自分自身は除く。 */
    fun launchable(): List<Pair<String, String>> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .map { it to label(it) }
            .sortedBy { it.second.lowercase() }
}
