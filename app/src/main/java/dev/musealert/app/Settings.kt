package dev.musealert.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.musealert.core.AlertPolicy
import dev.musealert.core.TimeWindow
import java.security.KeyStore
import java.time.LocalDate
import java.time.LocalTime
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Config(
    val targetPackages: Set<String> = setOf("com.google.android.youtube"),
    val thresholdMinutes: Int = 5,
    val repeatMinutes: Int = 5,
    val graceSeconds: Int = 60,
    val windowStart: LocalTime = LocalTime.MIDNIGHT,
    val windowEnd: LocalTime = LocalTime.MIDNIGHT,
    val gmailAddress: String = "",
    val mailCheckMinutes: Int = 5,
) {
    val policy: AlertPolicy
        get() = AlertPolicy(
            thresholdMillis = thresholdMinutes * 60_000L,
            repeatMillis = repeatMinutes * 60_000L,
            window = TimeWindow(windowStart, windowEnd),
        )
}

/** 設定と、サービスが覚えておく小さな状態。 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Config = Config(
        targetPackages = prefs.getStringSet("targets", null) ?: Config().targetPackages,
        thresholdMinutes = prefs.getInt("threshold", 5),
        repeatMinutes = prefs.getInt("repeat", 5),
        graceSeconds = prefs.getInt("grace", 60),
        windowStart = LocalTime.parse(prefs.getString("window_start", "00:00")),
        windowEnd = LocalTime.parse(prefs.getString("window_end", "00:00")),
        gmailAddress = prefs.getString("gmail", "")!!,
        mailCheckMinutes = prefs.getInt("mail_check", 5),
    )

    fun save(config: Config) {
        prefs.edit()
            .putStringSet("targets", config.targetPackages)
            .putInt("threshold", config.thresholdMinutes)
            .putInt("repeat", config.repeatMinutes)
            .putInt("grace", config.graceSeconds)
            .putString("window_start", config.windowStart.toString())
            .putString("window_end", config.windowEnd.toString())
            .putString("gmail", config.gmailAddress)
            .putInt("mail_check", config.mailCheckMinutes)
            .apply()
    }

    var appPassword: String
        get() = prefs.getString("app_password", null)?.let(Secret::decrypt) ?: ""
        set(value) = prefs.edit().putString("app_password", Secret.encrypt(value)).apply()

    var monitoring: Boolean
        get() = prefs.getBoolean("monitoring", false)
        set(value) = prefs.edit().putBoolean("monitoring", value).apply()

    var lastDigestDate: LocalDate?
        get() = prefs.getString("last_digest", null)?.let(LocalDate::parse)
        set(value) = prefs.edit().putString("last_digest", value?.toString()).apply()

    /** 画面に出すための、直近のメール処理の結果。 */
    var lastMailStatus: String
        get() = prefs.getString("mail_status", "")!!
        set(value) = prefs.edit().putString("mail_status", value).apply()

    fun isProcessed(messageId: String): Boolean =
        messageId in prefs.getStringSet("processed", emptySet())!!

    fun markProcessed(messageId: String) {
        val ids = prefs.getString("processed_order", "")!!.split('\n').filter { it.isNotEmpty() } + messageId
        val kept = ids.takeLast(MAX_PROCESSED_IDS)
        prefs.edit()
            .putString("processed_order", kept.joinToString("\n"))
            .putStringSet("processed", kept.toSet())
            .apply()
    }

    fun mailConfigured(config: Config = load()): Boolean =
        config.gmailAddress.isNotBlank() && appPassword.isNotBlank()

    private companion object {
        const val MAX_PROCESSED_IDS = 200
    }
}

/** アプリパスワードを Android Keystore の鍵で暗号化して保存する。 */
private object Secret {
    private const val ALIAS = "app_password_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String? = runCatching {
        val sealed = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
        }
        String(cipher.doFinal(sealed, 12, sealed.size - 12))
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }
}
