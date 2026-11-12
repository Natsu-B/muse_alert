package dev.musealert.app

import android.app.NotificationManager
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream

/** E2E テストで端末を操作するための小さな道具。 */
object Device {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context: Context = instrumentation.targetContext

    /** 監視対象として使うアプリ。どのエミュレーターにも入っている。 */
    const val TARGET = "com.android.settings"

    fun shell(command: String): String {
        val fd = instrumentation.uiAutomation.executeShellCommand(command)
        return FileInputStream(fd.fileDescriptor).use { it.readBytes().decodeToString() }.also { fd.close() }
    }

    fun grantPermissions() {
        shell("appops set ${context.packageName} GET_USAGE_STATS allow")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("svc power stayon true")
    }

    fun wake() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    fun sleep() = shell("input keyevent KEYCODE_SLEEP")

    fun openTarget() = shell("am start -W -n $TARGET/.Settings")

    fun goHome() = shell("input keyevent KEYCODE_HOME")

    /** 監視サービスはアプリが前面にあるときに起動する（バックグラウンドからは起動できない）。 */
    fun startMonitoring() {
        shell("am start -W -n ${context.packageName}/.MainActivity")
        UsageMonitorService.start(context)
        waitUntil(10_000, "監視サービスの起動") { Settings(context).monitoring }
    }

    fun alertTitle(): String? =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.id == Notifications.ID_ALERT }
            ?.notification?.extras?.getCharSequence("android.title")?.toString()

    fun waitUntil(timeoutMillis: Long, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(1_000)
        }
        throw AssertionError("${timeoutMillis / 1000} 秒待っても満たされない: $what")
    }

    /** 設定・ログ・通知を消し、監視を止めた状態に戻す。 */
    fun reset() {
        UsageMonitorService.stop(context)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.filesDir.resolve("sessions").deleteRecursively()
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }
}
