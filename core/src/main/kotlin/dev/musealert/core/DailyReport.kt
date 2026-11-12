package dev.musealert.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 1 日分の利用履歴をメール本文にする。
 * 人が読む部分と、Muse が読み取りやすい key=value の部分を併記する。
 *
 * アプリごとの合計にはすべてのセッションを含める。個々のセッションは、短い切り替えで
 * 一覧が埋まらないよう minListedMillis 以上のものだけを並べる。
 */
class DailyReport(
    private val date: LocalDate,
    private val sessions: List<UsageSession>,
    private val labels: Map<String, String>,
    private val generatedAt: Instant,
    private val zone: ZoneId,
    private val targets: Set<String> = emptySet(),
    private val minListedMillis: Long = 60_000,
) {
    /** partial は「その日がまだ終わっていない時点の報告」かどうか。 */
    fun subject(partial: Boolean): String =
        "$SUBJECT_TAG $date" + if (partial) " (途中経過)" else ""

    private data class AppSummary(
        val packageName: String,
        val totalMillis: Long,
        val count: Int,
        val longestMillis: Long,
        val alerts: Int,
    )

    fun body(): String = buildString {
        val now = generatedAt.toEpochMilli()
        val sorted = sessions.sortedBy { it.startMillis }
        val apps = sorted.groupBy { it.packageName }
            .map { (pkg, list) ->
                AppSummary(
                    packageName = pkg,
                    totalMillis = list.sumOf { it.durationMillis(now) },
                    count = list.size,
                    longestMillis = list.maxOf { it.durationMillis(now) },
                    alerts = list.sumOf { it.alertCount },
                )
            }
            .sortedByDescending { it.totalMillis }
        val listed = sorted.filter { it.durationMillis(now) >= minListedMillis }

        appendLine("$date のスマホ利用履歴（${clock(generatedAt)} 時点）")
        appendLine()
        if (apps.isEmpty()) {
            appendLine("アプリの使用はありませんでした。")
        } else {
            appendLine("合計 ${minutes(apps.sumOf { it.totalMillis })}分")
            appendLine()
            appendLine("アプリ別（長い順）:")
            apps.forEach { a ->
                val mark = if (a.packageName in targets) "（通知対象）" else ""
                appendLine("- ${label(a.packageName)}$mark: 合計 ${minutes(a.totalMillis)}分 / ${a.count}回 / 最長 ${minutes(a.longestMillis)}分 / 警告 ${a.alerts}回")
            }
            appendLine()
            appendLine("連続使用（${minutes(minListedMillis)}分以上）:")
            if (listed.isEmpty()) appendLine("- なし")
            listed.forEach { s ->
                val end = s.endMillis?.let { clock(Instant.ofEpochMilli(it)) } ?: "（継続中）"
                appendLine("- ${clock(Instant.ofEpochMilli(s.startMillis))}〜$end ${label(s.packageName)} ${minutes(s.durationMillis(now))}分（警告 ${s.alertCount}回）")
            }
        }

        appendLine()
        appendLine("---")
        appendLine("format: $FORMAT")
        appendLine("date: $date")
        appendLine("generated_at: ${iso(generatedAt)}")
        apps.forEach { a ->
            append("app: package=${a.packageName}")
            append(" target=${a.packageName in targets}")
            append(" total_seconds=${a.totalMillis / 1000}")
            append(" sessions=${a.count}")
            append(" longest_seconds=${a.longestMillis / 1000}")
            append(" alerts=${a.alerts}")
            appendLine(" label=\"${label(a.packageName).replace("\"", "'")}\"")
        }
        listed.forEach { s ->
            append("session: package=${s.packageName}")
            append(" start=${iso(Instant.ofEpochMilli(s.startMillis))}")
            append(" end=${s.endMillis?.let { iso(Instant.ofEpochMilli(it)) } ?: "ongoing"}")
            append(" seconds=${s.durationMillis(now) / 1000}")
            appendLine(" alerts=${s.alertCount}")
        }
    }

    private fun label(pkg: String) = labels[pkg] ?: pkg

    private fun minutes(millis: Long) = millis / 60_000

    private fun clock(instant: Instant) = instant.atZone(zone).format(CLOCK)

    private fun iso(instant: Instant) =
        ZonedDateTime.ofInstant(instant.truncatedTo(ChronoUnit.SECONDS), zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    companion object {
        const val SUBJECT_TAG = "[usage-report]"
        const val FORMAT = "muse-alert/v2"
        private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
    }
}
