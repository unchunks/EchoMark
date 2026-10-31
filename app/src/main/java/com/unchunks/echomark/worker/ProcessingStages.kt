package com.unchunks.echomark.worker

import androidx.work.CoroutineWorker
import androidx.work.workDataOf
import com.unchunks.components.llm.LlmException
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.ProcessingFailureReason
import com.unchunks.echomark.domain.bookmark.model.ProcessingStage
import com.unchunks.echomark.domain.bookmark.model.StageDurationKey
import com.unchunks.echomark.domain.bookmark.model.StageProgressReporter
import com.unchunks.echomark.domain.bookmark.model.contentKind
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import com.unchunks.echomark.domain.provider.NothingToAnalyzeException
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.ProcessingStatsRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * [stage] の段を処理中にして [block] を実行し、終えたら(失敗・中断も含めて)処理待ちに戻す。
 * 続きの段(チェーンの次のワーク)が始まれば、そこで処理中に戻る。
 * 本文の取得・中身の取り出しのように、それだけでは処理を終えない段に使う。
 *
 * [block] の中で [com.unchunks.echomark.domain.bookmark.model.reportStageProgress] で報告した進み具合は、
 * ワークの進み具合として画面に届く。[block] が true を返したら(段の仕事をやり遂げたら)、所要時間を記録して
 * 残り時間の目安に使う(失敗してすぐ終えた段は記録しない)。
 */
internal suspend fun CoroutineWorker.withProcessingStage(
    repository: BookmarkRepository,
    stats: ProcessingStatsRepository,
    bookmark: Bookmark,
    stage: ProcessingStage,
    block: suspend () -> Boolean
) {
    val stageStartedAt = repository.startProcessingStage(bookmark.id, stage)
    try {
        val completed = withContext(stageProgressReporter()) { block() }
        if (completed) {
            stats.recordStageDuration(
                StageDurationKey(stage, bookmark.contentKind()),
                System.currentTimeMillis() - stageStartedAt
            )
        }
    } finally {
        // 中断(取り消し・実行時間の上限)でも「処理中」のまま残さない
        withContext(NonCancellable) { repository.endProcessingStage(bookmark.id, stageStartedAt) }
    }
}

/**
 * 段の進み具合を、このワークの進み具合([BookmarkWorkScheduler.KEY_STAGE_PROGRESS])として書く報告先。
 * 書き込みが多くならないよう、1% 以上進んだときだけ書く。
 */
internal fun CoroutineWorker.stageProgressReporter(): StageProgressReporter {
    var last = -1f
    return StageProgressReporter { fraction ->
        if (fraction - last >= 0.01f || (fraction >= 1f && last < 1f)) {
            last = fraction
            setProgressAsync(workDataOf(BookmarkWorkScheduler.KEY_STAGE_PROGRESS to fraction))
        }
    }
}

/** 処理の失敗の例外から、失敗・準備待ち・再試行の理由を決める(画面に理由を出すため)。 */
internal fun failureReasonOf(e: Throwable): ProcessingFailureReason = when (e) {
    is ModelNotAvailableException -> ProcessingFailureReason.MODEL_NOT_AVAILABLE
    is NothingToAnalyzeException -> ProcessingFailureReason.NOTHING_TO_ANALYZE
    is LlmException.ApiKeyMissing -> ProcessingFailureReason.API_KEY_MISSING
    is LlmException.InvalidApiKey -> ProcessingFailureReason.API_KEY_INVALID
    is LlmException.BadRequest -> ProcessingFailureReason.BAD_REQUEST
    is LlmException.Refused -> ProcessingFailureReason.REFUSED
    is LlmException.Timeout -> ProcessingFailureReason.TIMEOUT
    is LlmException.EngineUnavailable, is LinkageError -> ProcessingFailureReason.ENGINE_UNAVAILABLE
    is LlmException.Network -> ProcessingFailureReason.NETWORK
    is LlmException.RateLimited -> ProcessingFailureReason.RATE_LIMITED
    is LlmException.ServerError -> ProcessingFailureReason.SERVER_ERROR
    else -> ProcessingFailureReason.UNKNOWN
}
