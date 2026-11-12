package dev.musealert.core

/** UsageStatsManager のイベントを、計測に必要な種類だけに絞ったもの。 */
data class UsageEvent(
    val timeMillis: Long,
    val type: Type,
    val packageName: String = "",
) {
    enum class Type { RESUMED, PAUSED, SCREEN_OFF, KEYGUARD_SHOWN }
}

/** 対象アプリの連続使用 1 回分。endMillis が null なら継続中。 */
data class UsageSession(
    val packageName: String,
    val startMillis: Long,
    val endMillis: Long? = null,
    val alertCount: Int = 0,
) {
    fun durationMillis(nowMillis: Long): Long = (endMillis ?: nowMillis) - startMillis
}

/**
 * イベント列から対象アプリの連続使用セッションを組み立てる。
 *
 * - 同じパッケージ内の画面遷移 (PAUSED → RESUMED) ではセッションを切らない。
 * - 別アプリに移っても gracePeriodMillis 以内に戻れば同じセッションとして扱う。
 * - 画面 OFF・ロックでは即座にセッションを終える。
 *
 * Android API にも時計にも依存しないので、イベント列を与えるだけでテストできる。
 */
class SessionTracker(
    private val isTarget: (String) -> Boolean,
    private val gracePeriodMillis: Long,
) {
    constructor(targetPackages: Set<String>, gracePeriodMillis: Long) :
        this({ it in targetPackages }, gracePeriodMillis)

    var active: UsageSession? = null
        private set

    /** 対象アプリから離れた時刻。null なら対象アプリが前面にある。 */
    private var awaySinceMillis: Long? = null

    /** 消化済みの警告回数。時間帯外で溜まった分を含むので alertCount 以上になる。 */
    var alertsConsumed: Int = 0
        private set

    private val completed = mutableListOf<UsageSession>()

    fun onEvent(event: UsageEvent) {
        expire(event.timeMillis)
        val current = active
        when (event.type) {
            UsageEvent.Type.RESUMED -> when {
                current != null && event.packageName == current.packageName -> awaySinceMillis = null
                isTarget(event.packageName) -> {
                    if (current != null) finish(awaySinceMillis ?: event.timeMillis)
                    active = UsageSession(event.packageName, event.timeMillis)
                    alertsConsumed = 0
                }
                current != null && awaySinceMillis == null -> awaySinceMillis = event.timeMillis
            }
            UsageEvent.Type.PAUSED -> {
                if (current != null && event.packageName == current.packageName && awaySinceMillis == null) {
                    awaySinceMillis = event.timeMillis
                }
            }
            UsageEvent.Type.SCREEN_OFF, UsageEvent.Type.KEYGUARD_SHOWN -> {
                if (current != null) finish(awaySinceMillis ?: event.timeMillis)
            }
        }
    }

    /** 猶予時間を過ぎても戻ってこなければ、離れた時刻でセッションを終える。 */
    fun expire(nowMillis: Long) {
        val away = awaySinceMillis ?: return
        if (active != null && nowMillis - away >= gracePeriodMillis) finish(away)
    }

    /** 前面に対象アプリがあるか（猶予中は false）。 */
    fun isInForeground(): Boolean = active != null && awaySinceMillis == null

    /**
     * 警告を出したことを記録する。回数は AlertPolicy.alertsDue の値をそのまま渡す。
     * 時間帯外で溜まった分をまとめて消化し、時間帯に入った直後に連続で鳴らないようにするため。
     */
    fun recordAlert(alertsDue: Int) {
        val current = active ?: return
        alertsConsumed = maxOf(alertsConsumed, alertsDue)
        active = current.copy(alertCount = current.alertCount + 1)
    }

    /** 終了したセッションを取り出す。取り出したものは内部から消える。 */
    fun drainCompleted(): List<UsageSession> = completed.toList().also { completed.clear() }

    private fun finish(endMillis: Long) {
        active?.let { completed += it.copy(endMillis = endMillis) }
        active = null
        awaySinceMillis = null
        alertsConsumed = 0
    }
}
