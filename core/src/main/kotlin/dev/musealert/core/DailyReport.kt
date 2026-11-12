package dev.musealert.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 1 日分の利用履歴をメール本文にする。
 * 人が読む部分と、Muse が読み取りやすい key=value の部分を併記する。
 */
class DailyReport(
    private val date: LocalDate,
    private val sessions: List<UsageSession>,
    private val labels: Map<String, String>,
    private val generatedAt: Instant,
    private val zone: ZoneId,
) {
    /** partial は「その日がまだ終わっていない時点の報告」かどうか。 */
    fun subject(partial: Boolean): String =
        "$SUBJECT_TAG $date" + if (partial) " (途中経過)" else ""

    fun body(): String = buildString {
        val now = generatedAt.toEpochMilli()
        val sorted = sessions.sortedBy { it.startMillis }

        appendLine("$date のスマホ利用履歴（${clock(generatedAt)} 時点）")
        appendLine()
        if (sorted.isEmpty()) {
            appendLine("対象アプリの連続使用はありませんでした。")
        } else {
            sorted.groupBy { it.packageName }.forEach { (pkg, list) ->
                val total = list.sumOf { it.durationMillis(now) }
                val longest = list.maxOf { it.durationMillis(now) }
                val alerts = list.sumOf { it.alertCount }
                appendLine("${label(pkg)}: 合計 ${minutes(total)}分 / ${list.size}回 / 最長 ${minutes(longest)}分 / 警告 ${alerts}回")
            }
            appendLine()
            appendLine("連続使用:")
            sorted.forEach { s ->
                val end = s.endMillis?.let { clock(Instant.ofEpochMilli(it)) } ?: "（継続中）"
                appendLine("- ${clock(Instant.ofEpochMilli(s.startMillis))}〜$end ${label(s.packageName)} ${minutes(s.durationMillis(now))}分（警告 ${s.alertCount}回）")
            }
        }

        appendLine()
        appendLine("---")
        appendLine("format: $FORMAT")
        appendLine("date: $date")
        appendLine("generated_at: ${iso(generatedAt)}")
        sorted.forEach { s ->
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
        ZonedDateTime.ofInstant(instant, zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    companion object {
        const val SUBJECT_TAG = "[usage-report]"
        const val FORMAT = "muse-alert/v1"
        private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
    }
}
