package dev.musealert.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.musealert.app.Device.TARGET
import dev.musealert.app.Device.waitUntil
import dev.musealert.core.UsageSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.Properties
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Session
import javax.mail.Store
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * テスト用メールサーバー (tools/fakemail) を相手に、リクエストへの返信と日次レポートを確かめる。
 * サーバーはホストで動かし、エミュレーターからは 10.0.2.2 で届く。
 * `-Pandroid.testInstrumentationRunnerArguments.fakemail=true` を付けたときだけ実行する。
 */
@RunWith(AndroidJUnit4::class)
class MailE2ETest {
    private val context = Device.context
    private val settings = Settings(context)
    private val log = SessionLog(context)
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)
    private val yesterday = today.minusDays(1)
    private val label = context.packageManager.run { getApplicationLabel(getApplicationInfo(TARGET, 0)) }

    private val session = Session.getInstance(
        Properties().apply {
            put("mail.smtp.host", HOST)
            put("mail.smtp.port", "2525")
            put("mail.imap.host", HOST)
            put("mail.imap.port", "1143")
        },
    )

    @Before
    fun setUp() {
        assumeTrue("fakemail=true のときだけ実行", InstrumentationRegistry.getArguments().getString("fakemail") == "true")
        Device.reset()
        Device.grantPermissions()
        Device.wake()
        clearMailbox()

        settings.save(Config(targets = listOf(TargetApp(TARGET)), gmailAddress = ADDRESS, mailCheckMinutes = 1))
        settings.appPassword = PASSWORD
        settings.lastDigestDate = today.minusDays(3)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("debug_mail_server", "$HOST:2525:$HOST:1143").commit()
    }

    @After
    fun tearDown() = Device.reset()

    @Test
    fun repliesToOwnRequestsSendsDigestAndIgnoresSpoofedOnes() {
        log.append(UsageSession(TARGET, at(yesterday, 22, 10), at(yesterday, 22, 28), alertCount = 2))
        log.append(UsageSession(TARGET, at(today, 8, 0), at(today, 8, 7), alertCount = 1))

        // 本人が送ったリクエスト (SMTP 経由なので INBOX と Sent の両方に入る)。
        val todayRequest = sendFromSelf("[usage-request]", "<today-request@test>")
        val datedRequest = sendFromSelf("[usage-request] $yesterday", "<dated-request@test>")
        // 外部からのなりすまし。From は本人だが Sent には無い。
        appendToInboxOnly("[usage-request]", "<spoofed-request@test>")

        Device.startMonitoring()

        waitUntil(60_000, "3 通のレポート") { reports().size >= 3 }
        Thread.sleep(5_000)
        val reports = reports()
        assertEquals("レポートの数: ${reports.map { it.subject }}", 3, reports.size)

        val partial = reports.single { it.inReplyTo == todayRequest }
        assertEquals("[usage-report] $today (途中経過)", partial.subject)
        assertTrue(partial.body, partial.body.contains("- 08:00〜08:07 $label 7分（警告 1回）"))
        assertTrue(partial.body, partial.body.contains("session: package=$TARGET start=${today}T08:00:00"))

        // 通知対象でないアプリ (このアプリ自身。テストで画面を開いている) も入る。ランチャーは入らない。
        assertTrue(partial.body, partial.body.contains("app: package=${context.packageName} target=false"))
        assertTrue(partial.body, partial.body.contains("app: package=$TARGET target=true"))
        val launchers = AppLabels(context).launchers()
        assertTrue("既定のランチャーが見つかる: $launchers", launchers.isNotEmpty() && TARGET !in launchers)
        launchers.forEach { assertTrue("ランチャー $it は入らない", !partial.body.contains("package=$it ")) }

        val dated = reports.single { it.inReplyTo == datedRequest }
        assertEquals("[usage-report] $yesterday", dated.subject)
        assertTrue(dated.body, dated.body.contains("- 22:10〜22:28 $label 18分（警告 2回）"))

        val digest = reports.single { it.inReplyTo == null }
        assertEquals("[usage-report] $yesterday", digest.subject)
        assertEquals(yesterday, settings.lastDigestDate)

        assertTrue("なりすましには返信しない", reports.none { it.inReplyTo == "<spoofed-request@test>" })
        reports.forEach { assertEquals("送信先は自分だけ", listOf(ADDRESS), it.to) }
    }

    private data class Report(val subject: String, val body: String, val inReplyTo: String?, val to: List<String>)

    private fun reports(): List<Report> = withInbox { inbox ->
        inbox.messages.filter { it.subject.orEmpty().startsWith("[usage-report]") }.map {
            Report(
                subject = it.subject,
                body = it.content.toString(),
                inReplyTo = it.getHeader("In-Reply-To")?.firstOrNull(),
                to = it.getRecipients(Message.RecipientType.TO).map { a -> (a as InternetAddress).address },
            )
        }
    }

    private fun sendFromSelf(subject: String, messageId: String): String {
        Transport.send(message(subject, messageId), ADDRESS, PASSWORD)
        return messageId
    }

    private fun appendToInboxOnly(subject: String, messageId: String) = withInbox(Folder.READ_WRITE) { inbox ->
        inbox.appendMessages(arrayOf(message(subject, messageId)))
    }

    private fun message(subject: String, messageId: String) = object : MimeMessage(session) {
        // Message-ID を固定する (既定では送信時に振り直される)。
        override fun updateMessageID() = setHeader("Message-ID", messageId)
    }.apply {
        setFrom(InternetAddress(ADDRESS))
        setRecipient(Message.RecipientType.TO, InternetAddress(ADDRESS))
        setSubject(subject)
        setText("Muse からのリクエスト")
        saveChanges()
    }

    private fun clearMailbox() = withStore { store ->
        for (name in listOf("INBOX", "Sent")) {
            val folder = store.getFolder(name)
            folder.open(Folder.READ_WRITE)
            folder.messages.forEach { it.setFlag(Flags.Flag.DELETED, true) }
            folder.close(true)
        }
    }

    private fun <T> withInbox(mode: Int = Folder.READ_ONLY, block: (Folder) -> T): T = withStore { store ->
        val inbox = store.getFolder("INBOX")
        inbox.open(mode)
        try {
            block(inbox)
        } finally {
            inbox.close(false)
        }
    }

    private fun <T> withStore(block: (Store) -> T): T {
        val store = session.getStore("imap")
        store.connect(ADDRESS, PASSWORD)
        try {
            return block(store)
        } finally {
            store.close()
        }
    }

    private fun at(date: LocalDate, hour: Int, minute: Int) =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private companion object {
        const val HOST = "10.0.2.2"
        const val ADDRESS = "muse-test@example.com"
        const val PASSWORD = "testpass"
    }
}
