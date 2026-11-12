package dev.musealert.app

import android.content.Context
import dev.musealert.core.UsageSession
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 終わったセッションを日付ごとのファイルに 1 行ずつ追記する。
 * 日付はセッションの開始時刻で決める。
 */
class SessionLog(context: Context, private val zone: ZoneId = ZoneId.systemDefault()) {
    private val dir = File(context.filesDir, "sessions")

    @Synchronized
    fun append(session: UsageSession) {
        val end = session.endMillis ?: return
        val file = fileFor(dateOf(session))
        // サービスを止めたときに継続中のセッションを書き、再起動後に読み直した同じセッションがまた来る。
        // 開始時刻とパッケージが同じなら、終了時刻の遅いほうで置き換える。
        dir.mkdirs()
        val existing = read(file)
        val same = existing.firstOrNull { it.packageName == session.packageName && it.startMillis == session.startMillis }
        if (same == null) {
            file.appendText(line(session.copy(endMillis = end)))
            return
        }
        if (same.endMillis!! >= end) return
        val merged = session.copy(alertCount = maxOf(same.alertCount, session.alertCount))
        file.writeText(existing.joinToString("") { if (it === same) line(merged) else line(it) })
    }

    private fun line(s: UsageSession) = "${s.packageName}\t${s.startMillis}\t${s.endMillis}\t${s.alertCount}\n"

    @Synchronized
    fun sessionsOn(date: LocalDate): List<UsageSession> = read(fileFor(date))

    fun dateOf(session: UsageSession): LocalDate =
        Instant.ofEpochMilli(session.startMillis).atZone(zone).toLocalDate()

    private fun fileFor(date: LocalDate) = File(dir, "$date.tsv")

    private fun read(file: File): List<UsageSession> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            val cols = line.split('\t')
            if (cols.size != 4) return@mapNotNull null
            UsageSession(cols[0], cols[1].toLong(), cols[2].toLong(), cols[3].toInt())
        }
    }
}
