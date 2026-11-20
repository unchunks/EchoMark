package com.unchunks.echomark.domain.provider

import com.unchunks.components.llm.ApiProvider

/** 選択肢として表示するモデル1件。 */
data class ApiModelPreset(val id: String, val label: String)

/**
 * AI 設定で選択肢として出すモデル(モデル ID は自由入力もできる)。
 * プリセットは各社の公式ドキュメント(2026-10 時点)の現行モデル ID。
 */
val ApiProvider.presets: List<ApiModelPreset>
    get() = when (this) {
        ApiProvider.CLAUDE -> listOf(
            ApiModelPreset("claude-opus-5-5", "Claude Opus 5.5(高性能・タグ付けとチャットの既定)"),
            ApiModelPreset("claude-sonnet-5-5", "Claude Sonnet 5.5(バランス)"),
            ApiModelPreset("claude-haiku-5-5", "Claude Haiku 5.5(最速・低コスト・要約の既定)"),
            ApiModelPreset("claude-haiku-4-5", "Claude Haiku 4.5(高速・低コスト)")
        )
        ApiProvider.GEMINI -> listOf(
            ApiModelPreset("gemini-3.8-flash", "Gemini 3.8 Flash(既定)"),
            ApiModelPreset("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite(低コスト)"),
            ApiModelPreset("gemini-3.1-pro-preview", "Gemini 3.1 Pro(プレビュー)")
        )
        ApiProvider.OPENAI -> listOf(
            ApiModelPreset("gpt-6.1-sol", "GPT-6.1 Sol(バランス・既定)"),
            ApiModelPreset("gpt-6-astra", "GPT-6 Astra(高性能)"),
            ApiModelPreset("gpt-6-luna", "GPT-6 Luna(低コスト)")
        )
    }
