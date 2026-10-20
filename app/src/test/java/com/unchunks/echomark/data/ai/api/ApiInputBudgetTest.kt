package com.unchunks.echomark.data.ai.api

import com.unchunks.echomark.domain.provider.ApiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** モデルごとの「1回で渡せる本文の長さ」と、それを超える本文の分割要約の設定。 */
class ApiInputBudgetTest {

    private fun budget(provider: ApiProvider, model: String) = ApiInputBudget.singleCallChars(provider, model)

    @Test
    fun 現行のClaudeは15万文字_Claude4系は6万文字() {
        listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5", "claude-opus-5", "claude-fable-5-1")
            .forEach { assertEquals(it, 150_000, budget(ApiProvider.CLAUDE, it)) }
        listOf("claude-haiku-4-5", "claude-haiku-4-5-20251001", "claude-sonnet-4-5", "claude-opus-4-1")
            .forEach { assertEquals(it, 60_000, budget(ApiProvider.CLAUDE, it)) }
    }

    @Test
    fun Gemini2以降は15万文字() {
        listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.1-pro-preview", "gemini-2.5-flash")
            .forEach { assertEquals(it, 150_000, budget(ApiProvider.GEMINI, it)) }
    }

    @Test
    fun OpenAIはGPT4_1以降が10万文字() {
        listOf("gpt-6.1-sol", "gpt-6-astra", "gpt-6-luna", "gpt-5", "gpt-4.1", "gpt-4.1-mini")
            .forEach { assertEquals(it, 100_000, budget(ApiProvider.OPENAI, it)) }
    }

    @Test
    fun 判断できないモデルは従来の2万文字() {
        val fallback = ApiInputBudget.FALLBACK_CHARS
        assertEquals(20_000, fallback)
        listOf("", "  ", "my-custom-model", "claude-3-5-haiku-latest", "claude-opus").forEach {
            assertEquals(it, fallback, budget(ApiProvider.CLAUDE, it))
        }
        listOf("gemini-1.5-flash", "gemma-3", "models/gemini-3.8-flash").forEach {
            assertEquals(it, fallback, budget(ApiProvider.GEMINI, it))
        }
        listOf("gpt-4o", "gpt-4", "gpt-4.0", "o3", "o4-mini", "chatgpt-4o-latest").forEach {
            assertEquals(it, fallback, budget(ApiProvider.OPENAI, it))
        }
        // 提供元と合わないモデル ID も、文脈長を判断しない
        assertEquals(fallback, budget(ApiProvider.CLAUDE, "gemini-3.8-flash"))
    }

    @Test
    fun 大文字小文字と前後の空白は無視する() {
        assertEquals(150_000, budget(ApiProvider.CLAUDE, " Claude-Opus-5-5 "))
        assertEquals(150_000, budget(ApiProvider.GEMINI, "GEMINI-3.8-FLASH"))
    }

    @Test
    fun 同梱のプリセットと既定のモデルはすべて従来より大きい上限になる() {
        ApiProvider.entries.forEach { provider ->
            (provider.presets.map { it.id } + provider.defaultModel).forEach { model ->
                assertTrue("$provider $model", budget(provider, model) > ApiInputBudget.FALLBACK_CHARS)
            }
        }
    }

    @Test
    fun 分割要約の設定は上限の3分の1ずつを4並行で要約する() {
        val config = ApiInputBudget.longTextConfig(ApiProvider.CLAUDE, "claude-opus-5-5")

        assertEquals(150_000, config.singleCallChars)
        assertEquals(50_000, config.chunkChars)
        assertEquals(8, config.maxChunks)
        assertEquals(4, config.parallelism)
        assertEquals(1_200, config.noteMaxChars)
        assertEquals(4 * 60 * 1000L, config.timeBudgetMillis)
        // 1回で渡せる上限を超えた本文は、間引く前に上限の 2 倍以上を読める
        assertTrue(config.chunkChars * config.maxChunks >= config.singleCallChars * 2)
    }

    @Test
    fun 判断できないモデルの設定は従来と同じ単位_部分だけ並行する() {
        val config = ApiInputBudget.longTextConfig(ApiProvider.OPENAI, "gpt-4o")

        assertEquals(20_000, config.singleCallChars)
        assertEquals(20_000, config.chunkChars)
        assertEquals(6, config.maxChunks)
        assertEquals(4, config.parallelism)
        assertEquals(config, ApiInputBudget.fallbackConfig())
    }
}
