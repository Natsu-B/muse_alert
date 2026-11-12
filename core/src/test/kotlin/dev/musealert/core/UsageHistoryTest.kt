package dev.musealert.core

import dev.musealert.core.UsageEvent.Type.PAUSED
import dev.musealert.core.UsageEvent.Type.RESUMED
import dev.musealert.core.UsageEvent.Type.SCREEN_OFF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val A = "com.example.a"
private const val B = "com.example.b"
private const val HOME = "com.example.launcher"
private const val MIN = 60_000L

class UsageHistoryTest {
    @Test
    fun `reconstructs sessions for every app`() {
        val events = listOf(
            UsageEvent(0, RESUMED, A),
            UsageEvent(3 * MIN, PAUSED, A),
            UsageEvent(3 * MIN, RESUMED, B),
            UsageEvent(5 * MIN, SCREEN_OFF),
            UsageEvent(10 * MIN, RESUMED, A),
        )
        val sessions = UsageHistory.reconstruct(events, gracePeriodMillis = MIN, nowMillis = 12 * MIN)

        assertEquals(listOf(A, B, A), sessions.map { it.packageName })
        assertEquals(3 * MIN, sessions[0].endMillis)
        assertEquals(5 * MIN, sessions[1].endMillis)
        assertNull("最後は継続中", sessions[2].endMillis)
    }

    @Test
    fun `excluded apps such as the launcher end the session but are not recorded`() {
        val events = listOf(
            UsageEvent(0, RESUMED, A),
            UsageEvent(2 * MIN, PAUSED, A),
            UsageEvent(2 * MIN, RESUMED, HOME),
        )
        val sessions = UsageHistory.reconstruct(events, MIN, nowMillis = 10 * MIN, include = { it != HOME })
        assertEquals(1, sessions.size)
        assertEquals(2 * MIN, sessions.single().endMillis)
    }

    @Test
    fun `logged sessions replace reconstructed ones for the same app`() {
        val logged = listOf(UsageSession(A, 0, 5 * MIN, alertCount = 1))
        val reconstructed = listOf(UsageSession(A, 0, 5 * MIN), UsageSession(B, 6 * MIN, 7 * MIN))
        val merged = UsageHistory.merge(logged, reconstructed)
        assertEquals(listOf(1, 0), merged.map { it.alertCount })
        assertEquals(listOf(A, B), merged.map { it.packageName })
    }
}
