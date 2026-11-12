package dev.musealert.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.musealert.app.Device.TARGET
import dev.musealert.app.Device.waitUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * 実際に監視サービスを動かし、別アプリ (設定アプリ) の使用を検知できるかを確かめる。
 * 閾値は最小の 1 分なので、1 件あたり数分かかる。
 */
@RunWith(AndroidJUnit4::class)
class MonitorE2ETest {
    private val settings = Settings(Device.context)
    private val log = SessionLog(Device.context)

    /**
     * テスト開始後に始まったセッション。サービスは起動時に直近のイベントを読み直すので、
     * 前のテストで開いた設定アプリのセッションも記録される。それを除く。
     */
    private var testStart = 0L
    private fun sessions() = log.sessionsOn(LocalDate.now()).filter { it.startMillis >= testStart }

    @Before
    fun setUp() {
        testStart = System.currentTimeMillis()
        Device.reset()
        Device.grantPermissions()
        Device.wake()
        Device.goHome()
        settings.save(Config(targets = listOf(TargetApp(TARGET, thresholdMinutes = 1, repeatMinutes = 1)), graceSeconds = 10))
    }

    @After
    fun tearDown() {
        Device.reset()
        Device.wake()
        Device.goHome()
    }

    @Test
    fun alertsAfterThresholdRepeatsAndLogsOnScreenOff() {
        Device.startMonitoring()
        Device.openTarget()
        val opened = System.currentTimeMillis()

        Thread.sleep(45_000)
        assertNull("1 分前に通知が出ている", Device.alertTitle())

        waitUntil(40_000, "1 分の警告") { Device.alertTitle()?.contains("1分間使用中") == true }
        waitUntil(70_000, "2 分の再通知") { Device.alertTitle()?.contains("2分間使用中") == true }

        Device.sleep()
        val screenOff = System.currentTimeMillis()
        Device.wake()

        // 画面 OFF はブロードキャストで受けるので、すぐに記録される。
        waitUntil(10_000, "画面 OFF でセッションが記録される") { sessions().isNotEmpty() }
        val session = sessions().single()
        assertEquals(TARGET, session.packageName)
        assertNear("開始時刻", opened, session.startMillis, 5_000)
        assertNear("終了時刻", screenOff, session.endMillis!!, 5_000)
        assertEquals(2, session.alertCount)
        waitUntil(15_000, "警告通知が消える") { Device.alertTitle() == null }
    }

    @Test
    fun shortSwitchKeepsSessionAndLongSwitchEndsIt() {
        Device.startMonitoring()
        Device.openTarget()
        val opened = System.currentTimeMillis()
        Thread.sleep(20_000)

        // 猶予 (10 秒) より短い離脱。同じセッションが続く。
        Device.goHome()
        Thread.sleep(4_000)
        Device.openTarget()
        Thread.sleep(20_000)

        // 猶予より長い離脱。離れた時刻でセッションが終わる。
        Device.goHome()
        val left = System.currentTimeMillis()
        // 電力を抑えるため、画面 ON 中のポーリングは 60 秒ごと。離脱に気づくのは最大でその分遅れる。
        waitUntil(90_000, "離脱でセッションが記録される") { sessions().isNotEmpty() }

        val session = sessions().single()
        assertNear("開始時刻（短い離脱でリセットされない）", opened, session.startMillis, 5_000)
        assertNear("終了時刻", left, session.endMillis!!, 5_000)
        assertTrue("1 分未満なので警告しない", session.alertCount == 0)
    }

    private fun assertNear(what: String, expected: Long, actual: Long, toleranceMillis: Long) {
        assertTrue("$what: 期待 $expected, 実際 $actual (差 ${actual - expected} ms)", kotlin.math.abs(actual - expected) <= toleranceMillis)
    }
}
