package com.unchunks.echomark.domain.provider

/**
 * LLM(主にクラウド API)の呼び出し失敗を種類ごとに表す。
 * [userMessage] は UI にそのまま出せる日本語。例外メッセージや userMessage に API キーを含めないこと。
 */
sealed class LlmException(
    val userMessage: String,
    cause: Throwable? = null
) : Exception(userMessage, cause) {

    /** 再試行で回復しうるか(レート制限・サーバー障害・通信断)。 */
    open val isRetryable: Boolean = false

    /** API キーが未設定。 */
    class ApiKeyMissing(val provider: ApiProvider) : LlmException(
        "${provider.displayName} の API キーが未設定です。設定 > AI 設定 で入力してください"
    )

    /** API キーが無効、または権限がない(HTTP 401/403)。 */
    class InvalidApiKey(val statusCode: Int, cause: Throwable? = null) : LlmException(
        "API キーが無効か、権限がありません。設定 > AI 設定 でキーを確認してください",
        cause
    )

    /** レート制限・利用上限(HTTP 429)。 */
    class RateLimited(val retryAfterSeconds: Long? = null, cause: Throwable? = null) : LlmException(
        "API の利用上限に達しました。しばらく待ってから再度お試しください",
        cause
    ) {
        override val isRetryable = true
    }

    /** 提供元のサーバー障害・過負荷(HTTP 5xx / 529)。 */
    class ServerError(val statusCode: Int, cause: Throwable? = null) : LlmException(
        "AI サービス側でエラーが発生しました($statusCode)。時間をおいて再度お試しください",
        cause
    ) {
        override val isRetryable = true
    }

    /** 通信できなかった(オフライン・タイムアウトなど)。 */
    class Network(cause: Throwable? = null) : LlmException(
        "AI サービスに接続できませんでした。ネットワーク接続を確認してください",
        cause
    ) {
        override val isRetryable = true
    }

    /** モデルの拒否・安全フィルタで回答が得られなかった。 */
    class Refused(val reason: String? = null) : LlmException(
        "この内容には AI が回答できませんでした(安全上の制限)"
    )

    /**
     * 生成が制限時間内に終わらなかった(端末内 AI が同じ文を繰り返し続けた場合など)。
     * 再試行しても同じ結果になりやすいため、自動では再試行しない。
     */
    class Timeout(val timeoutMillis: Long) : LlmException(
        "AI の応答に時間がかかりすぎたため中断しました"
    )

    /** リクエストが不正(存在しないモデル ID など。HTTP 400/404/422)。 */
    class BadRequest(val statusCode: Int, detail: String? = null, cause: Throwable? = null) : LlmException(
        buildString {
            append("AI へのリクエストが受け付けられませんでした($statusCode)。モデル ID などの設定を確認してください")
            if (!detail.isNullOrBlank()) append("\n詳細: ").append(detail.take(200))
        },
        cause
    )

    /** 応答の形式が想定外、またはその他の失敗。 */
    class Unexpected(detail: String? = null, cause: Throwable? = null) : LlmException(
        "AI の応答を処理できませんでした" + (detail?.let { ": ${it.take(200)}" } ?: ""),
        cause
    )
}

/** 埋め込みモデル(assets 同梱)が使えず、ベクトル化できないことを表す。 */
class EmbeddingUnavailableException(cause: Throwable? = null) :
    Exception("埋め込みモデルを利用できません", cause)

/** UI 向けの日本語エラーメッセージを返す。想定外の例外は [fallbackPrefix] + 例外メッセージ。 */
fun Throwable.toLlmUserMessage(fallbackPrefix: String): String = when (this) {
    is LlmException -> userMessage
    is ModelNotAvailableException -> MODEL_NOT_AVAILABLE_USER_MESSAGE
    else -> "$fallbackPrefix: ${message ?: "不明なエラー"}"
}

const val MODEL_NOT_AVAILABLE_USER_MESSAGE =
    "端末内 AI モデルが未取り込みです。設定 > AI 設定 でモデルを取り込むか、クラウド API を選んでください"
