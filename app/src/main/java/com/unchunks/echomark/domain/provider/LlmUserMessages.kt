package com.unchunks.echomark.domain.provider

import com.unchunks.components.llm.LlmException

/**
 * UI 向けの日本語エラーメッセージを返す。想定外の例外は [fallbackPrefix] + 例外メッセージ。
 * 設定で直せる失敗には、このアプリの設定画面の場所を添える。
 */
fun Throwable.toLlmUserMessage(fallbackPrefix: String): String = when (this) {
    is LlmException.ApiKeyMissing -> "$userMessage。$SETTINGS_LOCATION で入力してください"
    is LlmException.InvalidApiKey -> "$userMessage。$SETTINGS_LOCATION でキーを確認してください"
    is LlmException.EngineUnavailable -> "$userMessage。$SETTINGS_LOCATION でクラウド API を選んでください"
    is LlmException -> userMessage
    is ModelNotAvailableException -> MODEL_NOT_AVAILABLE_USER_MESSAGE
    else -> "$fallbackPrefix: ${message ?: "不明なエラー"}"
}

private const val SETTINGS_LOCATION = "設定 > AI 設定"

const val MODEL_NOT_AVAILABLE_USER_MESSAGE =
    "端末内 AI モデルが未取り込みです。$SETTINGS_LOCATION でモデルを取り込むか、クラウド API を選んでください"
