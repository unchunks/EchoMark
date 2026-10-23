package com.unchunks.echomark.domain.provider

import com.unchunks.components.llm.ApiProvider
import com.unchunks.components.llm.LlmException
import org.junit.Assert.assertEquals
import org.junit.Test

/** 設定で直せる失敗には、このアプリの設定画面の場所を添える(部品の LlmException の文言には含めない)。 */
class LlmUserMessagesTest {

    @Test
    fun 設定で直せる失敗は設定画面の場所を添える() {
        assertEquals(
            "Claude (Anthropic) の API キーが未設定です。設定 > AI 設定 で入力してください",
            LlmException.ApiKeyMissing(ApiProvider.CLAUDE).toLlmUserMessage("失敗")
        )
        assertEquals(
            "API キーが無効か、権限がありません。設定 > AI 設定 でキーを確認してください",
            LlmException.InvalidApiKey(401).toLlmUserMessage("失敗")
        )
        assertEquals(
            "端末内 AI を起動できませんでした。設定 > AI 設定 でクラウド API を選んでください",
            LlmException.EngineUnavailable().toLlmUserMessage("失敗")
        )
    }

    @Test
    fun それ以外は部品の文言か前置きと例外のメッセージ() {
        assertEquals(LlmException.Network().userMessage, LlmException.Network().toLlmUserMessage("失敗"))
        assertEquals(MODEL_NOT_AVAILABLE_USER_MESSAGE, ModelNotAvailableException().toLlmUserMessage("失敗"))
        assertEquals("失敗: boom", IllegalStateException("boom").toLlmUserMessage("失敗"))
    }
}
