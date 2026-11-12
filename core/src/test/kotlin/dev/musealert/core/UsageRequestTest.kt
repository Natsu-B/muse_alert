package dev.musealert.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class UsageRequestTest {
    private val today = LocalDate.of(2026, 11, 12)

    @Test
    fun `tag only means today`() {
        assertEquals(today, UsageRequest.parseDate("[usage-request]", today))
        assertEquals(today, UsageRequest.parseDate("Re: [Usage-Request] 今日の分", today))
    }

    @Test
    fun `explicit date`() {
        assertEquals(LocalDate.of(2026, 11, 11), UsageRequest.parseDate("[usage-request] 2026-11-11", today))
    }

    @Test
    fun `rejects other mails, future and invalid dates`() {
        assertNull(UsageRequest.parseDate("[usage-report] 2026-11-12", today))
        assertNull(UsageRequest.parseDate("[usage-request] 2026-11-13", today))
        assertNull(UsageRequest.parseDate("[usage-request] 2026-02-30", today))
    }
}
