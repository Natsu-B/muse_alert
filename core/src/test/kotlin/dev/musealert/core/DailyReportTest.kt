package dev.musealert.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

private const val YT = "com.google.android.youtube"

class DailyReportTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val date = LocalDate.of(2026, 11, 12)
    private fun at(h: Int, m: Int) = ZonedDateTime.of(date.atTime(h, m), zone).toInstant()

    @Test
    fun `formats sessions for people and machines`() {
        val sessions = listOf(
            UsageSession(YT, at(22, 10).toEpochMilli(), at(22, 28).toEpochMilli(), alertCount = 2),
            UsageSession(YT, at(23, 1).toEpochMilli()),
        )
        val report = DailyReport(date, sessions, mapOf(YT to "YouTube"), at(23, 7).plusMillis(301), zone)

        assertEquals("[usage-report] 2026-11-12 (途中経過)", report.subject(partial = true))
        val body = report.body()
        assertTrue(body, body.contains("YouTube: 合計 24分 / 2回 / 最長 18分 / 警告 2回"))
        assertTrue(body, body.contains("- 22:10〜22:28 YouTube 18分（警告 2回）"))
        assertTrue(body, body.contains("- 23:01〜（継続中） YouTube 6分（警告 0回）"))
        assertTrue(body, body.contains("session: package=$YT start=2026-11-12T22:10:00+09:00 end=2026-11-12T22:28:00+09:00 seconds=1080 alerts=2"))
        assertTrue(body, body.contains("end=ongoing seconds=360"))
        assertTrue("時刻は秒まで", body.contains("generated_at: 2026-11-12T23:07:00+09:00\n"))
    }

    @Test
    fun `reports an empty day`() {
        val body = DailyReport(date, emptyList(), emptyMap(), at(12, 0), zone).body()
        assertTrue(body.contains("対象アプリの連続使用はありませんでした。"))
        assertEquals("[usage-report] 2026-11-12", DailyReport(date, emptyList(), emptyMap(), at(12, 0), zone).subject(false))
    }
}
