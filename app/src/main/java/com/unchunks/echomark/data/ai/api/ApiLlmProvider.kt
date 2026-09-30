package com.unchunks.echomark.data.ai.api

import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.provider.LlmProvider
import javax.inject.Inject

class ApiLlmProvider @Inject constructor() : LlmProvider {
    override suspend fun analyze(text: String): BookmarkAnalysis {
        // TODO: ClaudeAPI / Gemini API / ChatGPT API を呼び出す(オプトイン)。
        // TODO: API キーは EncryptedSharedPreferences / Android Keystore で暗号化保存する(DataStore に平文で置かない)
        return BookmarkAnalysis(
            summary = "[API AI仮実装] ${text.take(50)}",
            tags = listOf("仮タグ"),
            category = "未分類"
        )
    }

    override suspend fun chat(
        userMessage: String,
        context: List<String>,
        history: List<ChatMessage>
    ): String {
        // TODO: 実際のLLM呼び出しに置き換える。context と history を含めたプロンプト/メッセージ列を組み立てて渡す
        return if (context.isEmpty()) {
            "[API AI仮実装] 関連する保存内容が見つかりませんでした。"
        } else {
            "[API AI仮実装] ${context.size}件の関連ブックマークを参照しました:\n${context.first().take(80)}"
        }
    }
}
