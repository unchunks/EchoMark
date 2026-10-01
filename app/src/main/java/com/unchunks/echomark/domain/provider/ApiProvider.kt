package com.unchunks.echomark.domain.provider

/** 選択肢として表示するモデル1件。 */
data class ApiModelPreset(val id: String, val label: String)

/**
 * クラウド API の提供元。モデル ID はプリセットから選ぶか自由入力できる。
 * プリセットは各社の公式ドキュメント(2026-10 時点)の現行モデル ID。
 */
enum class ApiProvider(
    val displayName: String,
    val defaultModel: String,
    val presets: List<ApiModelPreset>,
    /** API キーの入手先(設定画面の案内用) */
    val keyConsoleUrl: String
) {
    CLAUDE(
        displayName = "Claude (Anthropic)",
        defaultModel = "claude-opus-5-5",
        presets = listOf(
            ApiModelPreset("claude-opus-5-5", "Claude Opus 5.5(高性能・既定)"),
            ApiModelPreset("claude-sonnet-5-5", "Claude Sonnet 5.5(バランス)"),
            ApiModelPreset("claude-haiku-4-5", "Claude Haiku 4.5(高速・低コスト)")
        ),
        keyConsoleUrl = "https://platform.claude.com/"
    ),
    GEMINI(
        displayName = "Gemini (Google)",
        defaultModel = "gemini-3.8-flash",
        presets = listOf(
            ApiModelPreset("gemini-3.8-flash", "Gemini 3.8 Flash(既定)"),
            ApiModelPreset("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite(低コスト)"),
            ApiModelPreset("gemini-3.1-pro-preview", "Gemini 3.1 Pro(プレビュー)")
        ),
        keyConsoleUrl = "https://aistudio.google.com/apikey"
    ),
    OPENAI(
        displayName = "OpenAI",
        defaultModel = "gpt-6.1-sol",
        presets = listOf(
            ApiModelPreset("gpt-6.1-sol", "GPT-6.1 Sol(バランス・既定)"),
            ApiModelPreset("gpt-6-astra", "GPT-6 Astra(高性能)"),
            ApiModelPreset("gpt-6-luna", "GPT-6 Luna(低コスト)")
        ),
        keyConsoleUrl = "https://platform.openai.com/api-keys"
    )
}
