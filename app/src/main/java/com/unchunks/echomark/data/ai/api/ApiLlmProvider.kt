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

    override suspend fun categorize(text: String): String {
        // TODO: 実際のカテゴリ分類ロジックに置き換える
        return "未分類"
    }

    override suspend fun chat(userMessage: String, context: List<String>): String {
        // TODO: 実際のLLM呼び出しに置き換える。contextを含めたプロンプトを組み立てて渡す
        return if (context.isEmpty()) {
            "[ローカルAI仮実装] 関連する保存内容が見つかりませんでした。"
        } else {
            "[ローカルAI仮実装] ${context.size}件の関連ブックマークを参照しました:\n${context.first().take(80)}"
        }
    }
}
