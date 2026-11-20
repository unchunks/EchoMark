package com.unchunks.echomark.domain.bookmark.model

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * このブックマークの種類の処理の段(順番どおり)。「何段目か」の分母にする。
 * 再処理で済んでいる段は飛ばすため、途中の段から始まることがある。URL のリンク先がファイルだったときは、
 * 本文を取得した後に中身の取り出しの段が増える。
 * [com.unchunks.echomark.worker.BookmarkWorkScheduler] が積むワークの順に合わせる。
 */
fun Bookmark.processingStages(): List<ProcessingStage> = buildList {
    if (type == BookmarkType.URL) add(ProcessingStage.FETCH_CONTENT)
    if (type != BookmarkType.TEXT && (type != BookmarkType.URL || filePath != null)) add(ProcessingStage.EXTRACT_CONTENT)
    add(ProcessingStage.ANALYZE)
    add(ProcessingStage.EMBED)
}

/**
 * まだ通る段(今の段より後、処理待ちなら次から)。今の状態から見込んだもの:
 * 本文を取得済みの URL は取得の段を、要約済みなら要約・タグ付けの段を通らない。
 */
fun Bookmark.remainingStages(): List<ProcessingStage> {
    val stages = processingStages()
    val current = processing.stage.takeIf { aiStatus == AiStatus.PROCESSING }
    val after = if (current != null) stages.drop(stages.indexOf(current) + 1) else stages
    return after.filter { stage ->
        when (stage) {
            ProcessingStage.FETCH_CONTENT -> contentFetchedAt == null || content.isNullOrBlank()
            ProcessingStage.ANALYZE -> summary.isNullOrBlank()
            ProcessingStage.EXTRACT_CONTENT, ProcessingStage.EMBED -> true
        }
    }
}

/** 段の所要時間の記録の単位。同じ段でも中身の種類で大きく違う(音声の文字起こしと画像の読み取りなど)ため分ける。 */
data class StageDurationKey(val stage: ProcessingStage, val kind: ContentKind)

/** 処理の進み具合(詳細画面の表示用)。 */
data class ProcessingProgress(
    /** 今の段が何番目か(1 から)。処理待ちなら次に始まる段 */
    val stageNumber: Int,
    /** 通る段の数 */
    val stageCount: Int,
    /** 今の段の進み具合(0〜1)。段が報告していなければ null */
    val stageFraction: Float?,
    /** 処理を始めてからの経過時間。まだ始めていなければ null */
    val elapsedMillis: Long?,
    /** 残り時間の目安。過去の記録が無ければ null */
    val remainingMillis: Long?
)

/**
 * 処理の進み具合を見込む。残り時間は、段ごと・中身の種類ごとの過去の平均([averages])から出す。
 * 今の段は、進み具合が分かればその割合から、分からなければ平均から経過時間を引いて見込む。
 * 処理待ち・処理中でなければ null。
 */
fun Bookmark.processingProgress(
    now: Long,
    stageFraction: Float?,
    averages: Map<StageDurationKey, Long>
): ProcessingProgress? {
    if (aiStatus != AiStatus.PENDING && aiStatus != AiStatus.PROCESSING) return null
    val stages = processingStages()
    val kind = contentKind()
    val current = processing.stage.takeIf { aiStatus == AiStatus.PROCESSING && it in stages }
    val later = remainingStages()
    // 処理中なら今の段、処理待ちなら次に通る段が何番目か
    val number = (current ?: later.firstOrNull())?.let { stages.indexOf(it) + 1 } ?: stages.size
    val fraction = stageFraction?.coerceIn(0f, 1f)?.takeIf { current != null }

    val remainingCurrent: Long? = if (current == null) {
        0L
    } else {
        val stageElapsed = processing.stageStartedAt?.let { (now - it).coerceAtLeast(0) } ?: 0L
        when {
            // 進み具合から見込む(始めたばかりで割合が小さいうちは平均に任せる)
            fraction != null && fraction >= MIN_FRACTION_FOR_ESTIMATE ->
                (stageElapsed / fraction * (1 - fraction)).toLong()
            else -> averages[StageDurationKey(current, kind)]?.let { (it - stageElapsed).coerceAtLeast(0) }
        }
    }
    val laterAverages = later.map { averages[StageDurationKey(it, kind)] }
    val remaining = if (remainingCurrent == null || laterAverages.any { it == null }) {
        null
    } else {
        remainingCurrent + laterAverages.sumOf { it ?: 0L }
    }
    return ProcessingProgress(
        stageNumber = number,
        stageCount = stages.size,
        stageFraction = fraction,
        elapsedMillis = processing.startedAt?.let { (now - it).coerceAtLeast(0) },
        remainingMillis = remaining
    )
}

/** 処理にかかった時間(処理を始めてから終えるまで)。終えていない・記録が無ければ null。 */
fun Bookmark.processingDurationMillis(): Long? {
    val started = processing.startedAt ?: return null
    val finished = processing.finishedAt ?: return null
    return (finished - started).takeIf { it >= 0 }
}

private const val MIN_FRACTION_FOR_ESTIMATE = 0.05f

/**
 * 段の進み具合を報告する先。ワーカーがコルーチンの文脈に入れ、時間のかかる処理(ダウンロード・文字起こしなど)が
 * [reportStageProgress] で報告する。文脈に無ければ報告は捨てる。
 */
class StageProgressReporter(private val onProgress: (Float) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<StageProgressReporter>

    fun report(fraction: Float) = onProgress(fraction.coerceIn(0f, 1f))
}

/** 今の段の進み具合(0〜1)を報告する。 */
suspend fun reportStageProgress(fraction: Float) {
    currentCoroutineContext()[StageProgressReporter]?.report(fraction)
}
