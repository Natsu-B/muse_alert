package dev.musealert.core

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Muse からの履歴リクエストの件名を解釈する。
 *
 * 件名に `[usage-request]` を含むメールをリクエストとみなす。
 * 後ろに `2026-11-11` のような日付があればその日、なければ今日の履歴を返す。
 * 送信元の検証はメールを扱う側で行う。ここは件名だけを見る。
 */
object UsageRequest {
    const val SUBJECT_TAG = "[usage-request]"

    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

    /** リクエストなら対象日を、リクエストでなければ null を返す。未来の日付は受け付けない。 */
    fun parseDate(subject: String, today: LocalDate): LocalDate? {
        val index = subject.indexOf(SUBJECT_TAG, ignoreCase = true)
        if (index < 0) return null
        val rest = subject.substring(index + SUBJECT_TAG.length)
        val match = DATE.find(rest) ?: return today
        val date = try {
            LocalDate.parse(match.value)
        } catch (_: DateTimeParseException) {
            return null
        }
        return date.takeUnless { it.isAfter(today) }
    }
}
