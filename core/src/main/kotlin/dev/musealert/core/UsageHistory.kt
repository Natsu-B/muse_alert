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
     * 監視中に記録したセッションを優先して合わせる。記録があるアプリ (通知対象) は警告回数を
     * 持っているので記録を使い、それ以外のアプリは組み立て直した結果を使う。
     */
    fun merge(logged: List<UsageSession>, reconstructed: List<UsageSession>): List<UsageSession> {
        val loggedPackages = logged.map { it.packageName }.toSet()
        return (logged + reconstructed.filterNot { it.packageName in loggedPackages }).sortedBy { it.startMillis }
    }
}
