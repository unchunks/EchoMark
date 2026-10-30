package com.unchunks.echomark.ui.components

import com.unchunks.echomark.domain.bookmark.model.ProcessingFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 失敗の理由の言い方と、先に出す行動(#68)。 */
class FailureReasonTextTest {

    @Test
    fun すべての理由に一文と再試行待ちの言い方がある() {
        ProcessingFailureReason.entries.forEach { reason ->
            assertTrue(reason.name, reason.message().isNotBlank())
            assertTrue(reason.name, reason.message().endsWith("。"))
            assertTrue(reason.name, reason.retryingMessage().isNotBlank())
        }
    }

    @Test
    fun 設定を変えないと直らない理由は設定を先に_一時的な失敗は再試行を先に出す() {
        listOf(
            ProcessingFailureReason.MODEL_NOT_AVAILABLE,
            ProcessingFailureReason.API_KEY_MISSING,
            ProcessingFailureReason.API_KEY_INVALID,
            ProcessingFailureReason.BAD_REQUEST
        ).forEach { assertTrue(it.name, it.needsSetup) }
        listOf(
            ProcessingFailureReason.NETWORK,
            ProcessingFailureReason.RATE_LIMITED,
            ProcessingFailureReason.SERVER_ERROR,
            ProcessingFailureReason.TIMEOUT,
            ProcessingFailureReason.TOO_MANY_ATTEMPTS,
            ProcessingFailureReason.UNKNOWN
        ).forEach { assertFalse(it.name, it.needsSetup) }
    }

    @Test
    fun 通信の失敗は接続が戻ったら自動でやり直すと伝える() {
        assertEquals(
            "通信できなかったため、接続が戻ったら自動でやり直します。",
            ProcessingFailureReason.NETWORK.retryingMessage()
        )
    }
}
