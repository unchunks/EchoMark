package com.unchunks.echomark.worker

import com.unchunks.components.llm.LlmException
import com.unchunks.echomark.domain.bookmark.model.ProcessingFailureReason
import com.unchunks.echomark.domain.bookmark.model.ProcessingStage
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import com.unchunks.echomark.domain.provider.NothingToAnalyzeException
import com.unchunks.echomark.domain.repository.BookmarkRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * [stage] の段を処理中にして [block] を実行し、終えたら(失敗・中断も含めて)処理待ちに戻す。
 * 続きの段(チェーンの次のワーク)が始まれば、そこで処理中に戻る。
 * 本文の取得・中身の取り出しのように、それだけでは処理を終えない段に使う。
 */
internal suspend fun <T> withProcessingStage(
    repository: BookmarkRepository,
    bookmarkId: Long,
    stage: ProcessingStage,
    block: suspend () -> T
): T {
    val stageStartedAt = repository.startProcessingStage(bookmarkId, stage)
    try {
        return block()
    } finally {
        // 中断(取り消し・実行時間の上限)でも「処理中」のまま残さない
        withContext(NonCancellable) { repository.endProcessingStage(bookmarkId, stageStartedAt) }
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
