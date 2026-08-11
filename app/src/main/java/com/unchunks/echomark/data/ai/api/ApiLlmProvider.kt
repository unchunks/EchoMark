package com.unchunks.echomark.data.ai.api

import com.unchunks.echomark.domain.provider.LlmProvider
import javax.inject.Inject

class ApiLlmProvider @Inject constructor() : LlmProvider {
    override suspend fun summarize(text: String): String {
        // TODO: ClaudeAPI / Gemini API / ChatGPT API を呼び出す
        return "[API AI仮実装] ${text.take(50)}"
    }

    override suspend fun generateTags(text: String): List<String> {
        // TODO: API AIでタグ生成する
        return listOf("仮タグ")
    }
}