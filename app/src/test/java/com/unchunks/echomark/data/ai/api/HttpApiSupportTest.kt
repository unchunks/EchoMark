package com.unchunks.echomark.data.ai.api

import com.unchunks.echomark.domain.model.ChatRole
import com.unchunks.echomark.domain.provider.LlmException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpApiSupportTest {

    @Test
    fun 同じロールの連続はまとめ_先頭のアシスタントは除く() {
        val turns = normalizeTurns(
            listOf(
                ApiMessage(ChatRole.ASSISTANT, "挨拶"),
                ApiMessage(ChatRole.USER, "質問1"),
                ApiMessage(ChatRole.USER, "質問2"),
                ApiMessage(ChatRole.ASSISTANT, " "),
                ApiMessage(ChatRole.ASSISTANT, "回答")
            )
        )

        assertEquals(
            listOf(ApiMessage(ChatRole.USER, "質問1\n\n質問2"), ApiMessage(ChatRole.ASSISTANT, "回答")),
            turns
        )
    }

    @Test
    fun エラーメッセージからキーを伏せる() {
        val redacted = redactSecrets(
            "key my-secret-key / Incorrect API key provided: sk-proj-ab****cd / AIzaSyAbcdefghijklmnop",
            "my-secret-key"
        )

        assertFalse(redacted.contains("my-secret-key"))
        assertFalse(redacted.contains("sk-proj"))
        assertFalse(redacted.contains("AIzaSy"))
    }

    @Test
    fun HTTPステータスを例外の種類に変換する() {
        assertTrue(httpError(401, "", null, "k") is LlmException.InvalidApiKey)
        assertTrue(httpError(403, "", null, "k") is LlmException.InvalidApiKey)
        assertTrue(httpError(400, """{"error":{"details":[{"reason":"API_KEY_INVALID"}]}}""", null, "k") is LlmException.InvalidApiKey)
        assertEquals(12L, (httpError(429, "", "12", "k") as LlmException.RateLimited).retryAfterSeconds)
        assertTrue(httpError(502, "", null, "k") is LlmException.ServerError)
        assertTrue(httpError(404, """{"error":{"message":"model not found"}}""", null, "k") is LlmException.BadRequest)
    }

    @Test
    fun 認証情報のtoStringにキーを出さない() {
        assertFalse(ApiCredentials("secret-value", "m").toString().contains("secret-value"))
    }
}
