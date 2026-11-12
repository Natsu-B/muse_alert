package dev.musealert.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

private const val YT = "com.google.android.youtube"
private const val CHAT = "com.example.chat"

class DailyReportTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val date = LocalDate.of(2026, 11, 12)
    private fun at(h: Int, m: Int, s: Int = 0) = ZonedDateTime.of(date.atTime(h, m, s), zone).toInstant()
    private fun ms(h: Int, m: Int, s: Int = 0) = at(h, m, s).toEpochMilli()

    @Test
    fun `formats sessions for people and machines`() {
        val sessions = listOf(
            UsageSession(YT, ms(22, 10), ms(22, 28), alertCount = 2),
            UsageSession(YT, ms(23, 1)),
        )
        val report = DailyReport(date, sessions, mapOf(YT to "YouTube"), at(23, 7).plusMillis(301), zone, targets = setOf(YT))

        assertEquals("[usage-report] 2026-11-12 (途中経過)", report.subject(partial = true))
        val body = report.body()
        assertTrue(body, body.contains("- YouTube（通知対象）: 合計 24分 / 2回 / 最長 18分 / 警告 2回"))
        assertTrue(body, body.contains("- 22:10〜22:28 YouTube 18分（警告 2回）"))
        assertTrue(body, body.contains("- 23:01〜（継続中） YouTube 6分（警告 0回）"))
        assertTrue(body, body.contains("app: package=$YT target=true total_seconds=1440 sessions=2 longest_seconds=1080 alerts=2 label=\"YouTube\""))
        assertTrue(body, body.contains("session: package=$YT start=2026-11-12T22:10:00+09:00 end=2026-11-12T22:28:00+09:00 seconds=1080 alerts=2"))
        assertTrue(body, body.contains("end=ongoing seconds=360"))
        assertTrue("時刻は秒まで", body.contains("generated_at: 2026-11-12T23:07:00+09:00\n"))
    }

    @Test
    fun `includes every app, sorted by total, and lists only long sessions`() {
        val sessions = listOf(
            UsageSession(CHAT, ms(9, 0), ms(9, 0, 20)),
            UsageSession(CHAT, ms(9, 5), ms(9, 5, 30)),
            UsageSession(YT, ms(10, 0), ms(10, 3)),
            UsageSession(CHAT, ms(11, 0), ms(11, 4)),
        )
        val body = DailyReport(date, sessions, mapOf(YT to "YouTube", CHAT to "Chat"), at(12, 0), zone, targets = setOf(YT)).body()

        assertTrue(body, body.contains("合計 7分\n"))
        val chat = body.indexOf("- Chat: 合計 4分 / 3回 / 最長 4分 / 警告 0回")
        val yt = body.indexOf("- YouTube（通知対象）: 合計 3分")
        assertTrue(body, chat in 0 until yt)
        assertTrue(body, body.contains("app: package=$CHAT target=false total_seconds=290 sessions=3"))
        assertTrue(body, body.contains("- 11:00〜11:04 Chat 4分"))
        assertFalse("1 分未満は一覧に出さない", body.contains("09:00〜"))
        assertFalse(body.contains("start=2026-11-12T09:00:00"))
    }

    @Test
    fun `reports an empty day`() {
        val report = DailyReport(date, emptyList(), emptyMap(), at(12, 0), zone)
        assertTrue(report.body().contains("アプリの使用はありませんでした。"))
        assertEquals("[usage-report] 2026-11-12", report.subject(false))
    }
}
