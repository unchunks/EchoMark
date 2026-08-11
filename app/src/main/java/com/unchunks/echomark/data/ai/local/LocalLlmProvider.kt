package com.unchunks.echomark.data.ai.local

import com.unchunks.echomark.domain.provider.LlmProvider
import javax.inject.Inject

class LocalLlmProvider @Inject constructor() : LlmProvider {
    override suspend fun summarize(text: String): String {
        // TODO: MediaPipe LLM Inference API (Gemma 3 nano) と接続する
        return "[ローカルAI仮実装] ${text.take(50)}"
    }

    override suspend fun generateTags(text: String): List<String> {
        // TODO: ローカルAIでタグ生成する
        return listOf("仮タグ")
    }
}