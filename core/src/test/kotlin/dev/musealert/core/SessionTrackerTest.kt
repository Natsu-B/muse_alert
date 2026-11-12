package dev.musealert.core

import dev.musealert.core.UsageEvent.Type.KEYGUARD_SHOWN
import dev.musealert.core.UsageEvent.Type.PAUSED
import dev.musealert.core.UsageEvent.Type.RESUMED
import dev.musealert.core.UsageEvent.Type.SCREEN_OFF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val YT = "com.google.android.youtube"
private const val OTHER = "com.example.other"
private const val SEC = 1_000L
private const val MIN = 60 * SEC

class SessionTrackerTest {
    private val tracker = SessionTracker(setOf(YT), gracePeriodMillis = 60 * SEC)

    private fun event(t: Long, type: UsageEvent.Type, pkg: String = "") =
        tracker.onEvent(UsageEvent(t, type, pkg))

    @Test
    fun `starts a session when the target app is resumed`() {
        event(0, RESUMED, YT)
        assertEquals(YT, tracker.active?.packageName)
        assertEquals(4 * MIN, tracker.active?.durationMillis(4 * MIN))
    }

    @Test
    fun `ignores non-target apps`() {
        event(0, RESUMED, OTHER)
        assertNull(tracker.active)
    }

    @Test
    fun `screen transition inside the same app keeps the session`() {
        event(0, RESUMED, YT)
        event(2 * MIN, PAUSED, YT)
        event(2 * MIN + 200, RESUMED, YT)
        tracker.expire(10 * MIN)
        assertEquals(0L, tracker.active?.startMillis)
        assertTrue(tracker.isInForeground())
    }

    @Test
    fun `returning within the grace period keeps the session`() {
        event(0, RESUMED, YT)
        event(4 * MIN, PAUSED, YT)
        event(4 * MIN, RESUMED, OTHER)
        event(4 * MIN + 30 * SEC, PAUSED, OTHER)
        event(4 * MIN + 30 * SEC, RESUMED, YT)
        assertEquals(0L, tracker.active?.startMillis)
        assertTrue(tracker.drainCompleted().isEmpty())
    }

    @Test
    fun `leaving longer than the grace period ends the session at the time it was left`() {
        event(0, RESUMED, YT)
        event(4 * MIN, PAUSED, YT)
        event(4 * MIN, RESUMED, OTHER)
        event(5 * MIN + 30 * SEC, RESUMED, YT)

        val done = tracker.drainCompleted().single()
        assertEquals(4 * MIN, done.endMillis)
        assertEquals(5 * MIN + 30 * SEC, tracker.active?.startMillis)
    }

    @Test
    fun `expire ends the session without a new event`() {
        event(0, RESUMED, YT)
        event(4 * MIN, PAUSED, YT)
        tracker.expire(4 * MIN + 59 * SEC)
        assertFalse(tracker.isInForeground())
        assertTrue(tracker.drainCompleted().isEmpty())

        tracker.expire(5 * MIN)
        assertNull(tracker.active)
        assertEquals(4 * MIN, tracker.drainCompleted().single().endMillis)
    }

    @Test
    fun `screen off ends the session immediately`() {
        event(0, RESUMED, YT)
        event(3 * MIN, SCREEN_OFF)
        assertNull(tracker.active)
        assertEquals(3 * MIN, tracker.drainCompleted().single().endMillis)
    }

    @Test
    fun `screen off during the grace period ends at the time the app was left`() {
        event(0, RESUMED, YT)
        event(3 * MIN, PAUSED, YT)
        event(3 * MIN + 10 * SEC, KEYGUARD_SHOWN)
        assertEquals(3 * MIN, tracker.drainCompleted().single().endMillis)
    }

    @Test
    fun `switching to another target app starts a new session`() {
        val tracker = SessionTracker(setOf(YT, OTHER), gracePeriodMillis = 60 * SEC)
        tracker.onEvent(UsageEvent(0, RESUMED, YT))
        tracker.onEvent(UsageEvent(2 * MIN, PAUSED, YT))
        tracker.onEvent(UsageEvent(2 * MIN, RESUMED, OTHER))
        assertEquals(OTHER, tracker.active?.packageName)
        assertEquals(2 * MIN, tracker.drainCompleted().single().endMillis)
    }

    @Test
    fun `alert count resets with a new session`() {
        event(0, RESUMED, YT)
        tracker.recordAlert(3)
        assertEquals(3, tracker.alertsConsumed)
        assertEquals(1, tracker.active?.alertCount)
        event(1 * MIN, SCREEN_OFF)
        event(2 * MIN, RESUMED, YT)
        assertEquals(0, tracker.alertsConsumed)
        assertEquals(1, tracker.drainCompleted().single().alertCount)
    }
}
