package com.unchunks.echomark.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.unchunks.echomark.domain.bookmark.model.ProcessingProgress
import kotlinx.coroutines.delay

/** 経過・所要時間の表示(例: "45秒"、"3分12秒"、"1時間5分")。 */
fun formatElapsed(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}時間${minutes}分"
        minutes > 0 -> "${minutes}分${seconds}秒"
        else -> "${seconds}秒"
    }
}

/** 残り時間の目安の表示。細かく出しても当たらないため、分単位に切り上げる(例: "1分未満"、"約3分"、"約1時間10分")。 */
fun formatRemaining(millis: Long): String {
    if (millis < 60_000) return "1分未満"
    val totalMinutes = (millis + 59_999) / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0L -> "約${minutes}分"
        minutes == 0L -> "約${hours}時間"
        else -> "約${hours}時間${minutes}分"
    }
}

/** 進み具合の1行の説明(例: "段階 2/4 ・ 経過 1分20秒 ・ 残り約3分")。 */
fun ProcessingProgress.summaryText(): String = buildList {
    add("段階 $stageNumber/$stageCount")
    stageFraction?.let { add("この段階 ${(it * 100).toInt()}%") }
    elapsedMillis?.let { add("経過 ${formatElapsed(it)}") }
    remainingMillis?.let { add("残り${formatRemaining(it)}") }
}.joinToString(" ・ ")

/**
 * [intervalMillis] ごとに進む今の時刻(経過時間・残り時間の表示を更新する)。
 * [enabled] が false の間は進めない(時刻を出さない表示で、画面を更新し続けない)。
 */
@Composable
fun rememberTickingNow(enabled: Boolean, intervalMillis: Long = 1_000L): State<Long> =
    produceState(System.currentTimeMillis(), enabled) {
        value = System.currentTimeMillis()
        while (enabled) {
            delay(intervalMillis)
            value = System.currentTimeMillis()
        }
    }
