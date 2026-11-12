package dev.musealert.core

/**
 * レポート用に、1 日の全アプリの使用をイベント列から組み立て直す。
 *
 * 常時の計測は通知対象のアプリだけにして、それ以外のアプリはレポートを作るときに
 * OS が保存しているイベントから計算する。監視中に余計な処理を増やさないため。
 */
object UsageHistory {
    /**
     * イベント列から全アプリのセッションを作る。判定は SessionTracker と同じ。
     * 最後まで続いていたセッションは endMillis = null（継続中）で返す。
     */
    fun reconstruct(
        events: List<UsageEvent>,
        gracePeriodMillis: Long,
        nowMillis: Long,
        include: (String) -> Boolean = { true },
    ): List<UsageSession> {
        val tracker = SessionTracker(include, gracePeriodMillis)
        events.sortedBy { it.timeMillis }.forEach(tracker::onEvent)
        tracker.expire(nowMillis)
        return tracker.drainCompleted() + listOfNotNull(tracker.active)
    }

    /**
     * 監視中に記録したセッション (警告回数を持つ) を優先して合わせる。
     * 組み立て直したセッションのうち、同じアプリの記録と時間が重なるものは捨てる。
     * 監視を止めていた間の使用は、組み立て直したものが残る。
     */
    fun merge(logged: List<UsageSession>, reconstructed: List<UsageSession>, nowMillis: Long): List<UsageSession> {
        fun overlaps(a: UsageSession, b: UsageSession) =
            a.packageName == b.packageName &&
                a.startMillis < (b.endMillis ?: nowMillis) && b.startMillis < (a.endMillis ?: nowMillis)
        return (logged + reconstructed.filterNot { r -> logged.any { overlaps(it, r) } }).sortedBy { it.startMillis }
    }
}
