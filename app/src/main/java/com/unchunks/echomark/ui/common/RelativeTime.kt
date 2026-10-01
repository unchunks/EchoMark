package com.unchunks.echomark.ui.common

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
private const val DAY_MILLIS = 24 * HOUR_MILLIS

/**
 * [timeMillis] が [nowMillis] からどれだけ前かを「3日前」のような短い日本語で返す。
 * 経過時間だけで決める(暦の日付はまたがない)。未来の時刻(端末の時計ずれ)は「たった今」とする。
 *
 * - 1分未満: たった今 / 1時間未満: N分前 / 1日未満: N時間前 / 7日未満: N日前
 * - 30日未満: N週間前 / 365日未満: Nか月前(30日を1か月とする) / それ以上: N年前
 */
fun formatRelativeTime(timeMillis: Long, nowMillis: Long): String {
    val elapsed = nowMillis - timeMillis
    if (elapsed < MINUTE_MILLIS) return "たった今"
    if (elapsed < HOUR_MILLIS) return "${elapsed / MINUTE_MILLIS}分前"
    if (elapsed < DAY_MILLIS) return "${elapsed / HOUR_MILLIS}時間前"

    val days = elapsed / DAY_MILLIS
    return when {
        days < 7 -> "${days}日前"
        days < 30 -> "${days / 7}週間前"
        days < 365 -> "${days / 30}か月前"
        else -> "${days / 365}年前"
    }
}
