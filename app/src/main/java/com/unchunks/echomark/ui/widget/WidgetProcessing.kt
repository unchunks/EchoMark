package com.unchunks.echomark.ui.widget

import com.unchunks.echomark.domain.bookmark.model.ProcessingQueue
import com.unchunks.echomark.domain.bookmark.model.StageDurationKey
import com.unchunks.echomark.domain.bookmark.model.processingProgress
import com.unchunks.echomark.ui.components.formatRemaining

/**
 * ウィジェットに出す AI 処理の進み具合(#63 の [processingProgress] から作る)。
 * ウィジェットは頻繁に描き直せないので、画面に出す粒度(進み具合は 5% ごと・残り時間は分)にそろえてある。
 * 同じ値なら描き直さない(RediscoverWidgetUpdater が値の変化で判断する)ためでもある。
 *
 * @param running 処理中の件数
 * @param waiting 処理待ちの件数
 * @param fraction 処理中のものの進み具合の平均(0〜1)。処理中のものが無ければ null
 * @param remainingMinutes 処理中・処理待ちを全部終えるまでの目安(分)。見込めなければ null
 */
data class WidgetProcessing(
    val running: Int,
    val waiting: Int,
    val fraction: Float?,
    val remainingMinutes: Int?
) {
    /** 「AI 処理中 2件・待ち 3件」 */
    fun title(): String = buildString {
        if (running > 0) append("AI 処理中 ${running}件") else append("AI 処理待ち ${waiting}件")
        if (running > 0 && waiting > 0) append("・待ち ${waiting}件")
    }

    /** 「残り約3分」。見込めなければ、処理中の進み具合の割合だけ(それも無ければ null)。 */
    fun detail(): String? = when {
        remainingMinutes != null -> "残り${formatRemaining(remainingMinutes * 60_000L)}"
        fraction != null -> "${(fraction * 100).toInt()}% 完了"
        else -> null
    }

    /** 読み上げ用の 1 文 */
    fun description(): String = listOfNotNull(title(), detail()).joinToString("、")
}

/** 処理の状況([ProcessingQueue])から [WidgetProcessing] を作る純粋な処理。 */
object WidgetProcessingSummary {
    /** 残り時間を足し合わせる処理待ちの上限。それ以上たまっているときは、見込みが当たらないので出さない。 */
    const val MAX_WAITING_FOR_ESTIMATE = 200

    /** 進み具合を丸める単位 */
    private const val FRACTION_STEP = 0.05f

    /**
     * 処理中・処理待ちが無ければ null(ウィジェットに何も出さない)。
     * @param stageFractions 処理中のブックマークの ID → 今の段の進み具合(段が報告しているものだけ)
     * @param averages 段・中身の種類ごとの平均の所要時間(残り時間の見込みに使う)
     */
    fun summarize(
        queue: ProcessingQueue,
        now: Long,
        stageFractions: Map<Long, Float>,
        averages: Map<StageDurationKey, Long>
    ): WidgetProcessing? {
        if (queue.running.isEmpty() && queue.waiting.isEmpty()) return null

        val runningProgress = queue.running.map { it.processingProgress(now, stageFractions[it.id], averages) }
        // 何段目か(0 始まり)と今の段の進み具合から、そのブックマーク全体の進み具合を出す
        val fractions = runningProgress.filterNotNull().map { progress ->
            ((progress.stageNumber - 1) + (progress.stageFraction ?: 0f)) / progress.stageCount.coerceAtLeast(1)
        }
        val fraction = fractions.takeIf { it.isNotEmpty() }?.average()?.toFloat()?.let(::roundFraction)

        val remaining: Long? = if (queue.waiting.size > MAX_WAITING_FOR_ESTIMATE) {
            null
        } else {
            val all = runningProgress + queue.waiting.map { it.processingProgress(now, null, averages) }
            if (all.any { it?.remainingMillis == null }) null else all.sumOf { it?.remainingMillis ?: 0L }
        }
        return WidgetProcessing(
            running = queue.running.size,
            waiting = queue.waiting.size,
            fraction = fraction,
            remainingMinutes = remaining?.let { ((it + 59_999) / 60_000).toInt().coerceAtLeast(1) }
        )
    }

    private fun roundFraction(value: Float): Float =
        (Math.round(value.coerceIn(0f, 1f) / FRACTION_STEP) * FRACTION_STEP).coerceIn(0f, 1f)
}
