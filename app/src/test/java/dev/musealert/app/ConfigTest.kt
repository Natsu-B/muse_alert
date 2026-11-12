package dev.musealert.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigTest {
    private val config = Config(
        targets = listOf(
            TargetApp("com.google.android.youtube", thresholdMinutes = 5, repeatMinutes = 5),
            TargetApp("com.twitter.android", thresholdMinutes = 10, repeatMinutes = 0),
        ),
    )

    @Test
    fun `each app has its own policy`() {
        assertEquals(5 * 60_000L, config.policyFor("com.google.android.youtube")!!.thresholdMillis)
        val x = config.policyFor("com.twitter.android")!!
        assertEquals(10 * 60_000L, x.thresholdMillis)
        assertEquals(1, x.alertsDue(60 * 60_000L))
    }

    @Test
    fun `apps that are not targets have no policy`() {
        assertNull(config.policyFor("com.example.other"))
        assertEquals(setOf("com.google.android.youtube", "com.twitter.android"), config.targetPackages)
    }
}
