package dev.musealert.app

import com.sun.mail.imap.IMAPFolder
import java.util.Date
import java.util.Properties
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Session
import javax.mail.Store
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.AndTerm
import javax.mail.search.ComparisonTerm
import javax.mail.search.MessageIDTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.SubjectTerm

/**
 * 専用 Gmail とのやりとり。SMTP で自分宛てに送り、IMAP でリクエストを探す。
 * 認証は Google アカウントの「アプリパスワード」。ブロッキング処理なので IO スレッドで呼ぶ。
 */
class MailClient(
    private val address: String,
    private val appPassword: String,
    private val server: Server = Server.GMAIL,
) {

    data class Request(val messageId: String, val subject: String)

    /** 接続先。通常は Gmail。デバッグビルドではテスト用サーバーに差し替えられる。 */
    data class Server(
        val smtpHost: String,
        val smtpPort: Int,
        val imapHost: String,
        val imapPort: Int,
        val ssl: Boolean = true,
    ) {
        companion object {
            val GMAIL = Server("smtp.gmail.com", 465, "imap.gmail.com", 993)
        }
    }

    private val imapProtocol = if (server.ssl) "imaps" else "imap"

    private val session: Session = Session.getInstance(
        Properties().apply {
            put("mail.smtp.host", server.smtpHost)
            put("mail.smtp.port", server.smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.ssl.enable", server.ssl.toString())
            put("mail.$imapProtocol.host", server.imapHost)
            put("mail.$imapProtocol.port", server.imapPort.toString())
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "15000")
            put("mail.$imapProtocol.connectiontimeout", "15000")
            put("mail.$imapProtocol.timeout", "15000")
        },
    )

    /** 宛先は常に自分。リクエストの Reply-To などには従わない。 */
    fun sendToSelf(subject: String, body: String, inReplyTo: String? = null) {
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(address))
            setRecipient(Message.RecipientType.TO, InternetAddress(address))
            setSubject(subject, "UTF-8")
            setText(body, "UTF-8")
            if (inReplyTo != null) {
                setHeader("In-Reply-To", inReplyTo)
                setHeader("References", inReplyTo)
            }
        }
        Transport.send(message, address, appPassword)
    }

    /**
     * 受信箱から subjectTag を含むメールを探す。
     *
     * From が自分であるだけでは偽装できるので、同じ Message-ID が「送信済み」にもあること、
     * つまりこのアカウント自身が送ったメールであることを確かめる。
     */
    fun findRequests(subjectTag: String, since: Date): List<Request> = withStore { store ->
        val sent = findSentFolder(store) ?: error("送信済みフォルダが見つかりません")
        val inbox = store.getFolder("INBOX")
        inbox.open(Folder.READ_ONLY)
        sent.open(Folder.READ_ONLY)
        try {
            inbox.search(AndTerm(SubjectTerm(subjectTag), ReceivedDateTerm(ComparisonTerm.GE, since)))
                .mapNotNull { message ->
                    val id = (message as MimeMessage).messageID ?: return@mapNotNull null
                    val fromSelf = message.from.orEmpty()
                        .any { (it as? InternetAddress)?.address.equals(address, ignoreCase = true) }
                    if (!fromSelf || sent.search(MessageIDTerm(id)).isEmpty()) return@mapNotNull null
                    Request(id, message.subject.orEmpty())
                }
        } finally {
            inbox.close(false)
            sent.close(false)
        }
    }

    /** 接続とログインだけを試す。設定画面の確認用。 */
    fun checkLogin() = withStore { }

    private fun <T> withStore(block: (Store) -> T): T {
        val store = session.getStore(imapProtocol)
        store.connect(address, appPassword)
        try {
            return block(store)
        } finally {
            store.close()
        }
    }

    /** Gmail の送信済みフォルダは表示言語で名前が変わるので、SPECIAL-USE 属性で探す。 */
    private fun findSentFolder(store: Store): Folder? =
        store.defaultFolder.list("*").firstOrNull { folder ->
            (folder as? IMAPFolder)?.attributes?.any { it.equals("\\Sent", ignoreCase = true) } == true
        }
}
