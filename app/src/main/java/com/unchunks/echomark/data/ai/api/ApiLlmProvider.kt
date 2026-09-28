package com.unchunks.echomark.data.ai.api

import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.provider.LlmProvider
import javax.inject.Inject

class ApiLlmProvider @Inject constructor() : LlmProvider {
    override suspend fun analyze(text: String): BookmarkAnalysis {
        // TODO: ClaudeAPI / Gemini API / ChatGPT API を呼び出す(オプトイン・キーは暗号化保存)
        return BookmarkAnalysis(
            summary = "[API AI仮実装] ${text.take(50)}",
            tags = listOf("仮タグ"),
            category = "未分類"
        )
    }

    override suspend fun chat(userMessage: String, context: List<String>): String {
        // TODO: 実際のLLM呼び出しに置き換える。contextを含めたプロンプトを組み立てて渡す
        return if (context.isEmpty()) {
            "[API AI仮実装] 関連する保存内容が見つかりませんでした。"
        } else {
            "[API AI仮実装] ${context.size}件の関連ブックマークを参照しました:\n${context.first().take(80)}"
        }
    }
}
