package dev.musealert.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

private const val MIN = 60_000L

class AlertPolicyTest {
    private val policy = AlertPolicy(thresholdMillis = 5 * MIN, repeatMillis = 5 * MIN)
    private val noon = LocalTime.NOON

    @Test
    fun `does not alert at 4 minutes 59 seconds`() {
        assertFalse(policy.shouldAlert(5 * MIN - 1_000, 0, noon))
    }

    @Test
    fun `alerts at 5 minutes`() {
        assertTrue(policy.shouldAlert(5 * MIN, 0, noon))
    }

    @Test
    fun `alerts again every repeat interval`() {
        assertFalse(policy.shouldAlert(9 * MIN, 1, noon))
        assertTrue(policy.shouldAlert(10 * MIN, 1, noon))
        assertEquals(3, policy.alertsDue(15 * MIN))
    }

    @Test
    fun `no repeat when interval is zero`() {
        val once = policy.copy(repeatMillis = 0)
        assertEquals(1, once.alertsDue(60 * MIN))
    }

    @Test
    fun `does not alert outside the window`() {
        val night = policy.copy(window = TimeWindow(LocalTime.of(23, 0), LocalTime.of(7, 0)))
        assertFalse(night.shouldAlert(10 * MIN, 0, LocalTime.of(22, 59)))
        assertTrue(night.shouldAlert(10 * MIN, 0, LocalTime.of(23, 0)))
        assertTrue(night.shouldAlert(10 * MIN, 0, LocalTime.of(3, 0)))
        assertFalse(night.shouldAlert(10 * MIN, 0, LocalTime.of(7, 0)))
    }

    @Test
    fun `daytime window`() {
        val day = TimeWindow(LocalTime.of(9, 0), LocalTime.of(18, 0))
        assertTrue(LocalTime.of(9, 0) in day)
        assertFalse(LocalTime.of(18, 0) in day)
        assertTrue(LocalTime.of(3, 0) in TimeWindow.ALL_DAY)
    }
}
