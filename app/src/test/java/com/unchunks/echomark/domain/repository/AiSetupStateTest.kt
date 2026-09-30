package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.provider.ApiProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class AiSetupStateTest {

    @Test
    fun 端末内実行はモデルの有無だけで決まる() {
        assertEquals(
            AiSetupState.READY,
            AiSetupState.of(LlmBackend.LOCAL, isLocalModelInstalled = true, ApiProvider.CLAUDE, emptySet())
        )
        // API キーがあっても、端末内実行を選んでいてモデルが無ければ使えない
        assertEquals(
            AiSetupState.LOCAL_MODEL_MISSING,
            AiSetupState.of(LlmBackend.LOCAL, isLocalModelInstalled = false, ApiProvider.CLAUDE, setOf(ApiProvider.CLAUDE))
        )
    }

    @Test
    fun クラウドAPIは選択中の提供元のキーがあるときだけ使える() {
        assertEquals(
            AiSetupState.READY,
            AiSetupState.of(LlmBackend.API, isLocalModelInstalled = false, ApiProvider.GEMINI, setOf(ApiProvider.GEMINI))
        )
        assertEquals(
            AiSetupState.API_KEY_MISSING,
            AiSetupState.of(LlmBackend.API, isLocalModelInstalled = true, ApiProvider.GEMINI, setOf(ApiProvider.OPENAI))
        )
    }
}
