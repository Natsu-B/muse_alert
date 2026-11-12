package dev.musealert.core

import java.time.LocalTime

/**
 * 監視する時間帯。start == end なら終日。日付をまたぐ指定 (23:00〜07:00) も扱える。
 */
data class TimeWindow(val start: LocalTime, val end: LocalTime) {
    operator fun contains(time: LocalTime): Boolean = when {
        start == end -> true
        start < end -> time >= start && time < end
        else -> time >= start || time < end
    }

    companion object {
        val ALL_DAY = TimeWindow(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT)
    }
}

/**
 * いつ警告するかを決める。最初の警告は thresholdMillis、その後は repeatMillis ごと。
 * repeatMillis が 0 以下なら再通知しない。
 */
data class AlertPolicy(
    val thresholdMillis: Long,
    val repeatMillis: Long,
    val window: TimeWindow = TimeWindow.ALL_DAY,
) {
    /** 経過時間に対して、これまでに出しているべき警告の回数。 */
    fun alertsDue(elapsedMillis: Long): Int = when {
        elapsedMillis < thresholdMillis -> 0
        repeatMillis <= 0 -> 1
        else -> 1 + ((elapsedMillis - thresholdMillis) / repeatMillis).toInt()
    }

    /** 新しく警告を出すべきか。時間帯の外では出さない。 */
    fun shouldAlert(elapsedMillis: Long, alertsConsumed: Int, localTime: LocalTime): Boolean =
        localTime in window && alertsDue(elapsedMillis) > alertsConsumed
}
