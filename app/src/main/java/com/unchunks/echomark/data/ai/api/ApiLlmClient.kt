package com.unchunks.echomark.data.ai.api

import com.unchunks.echomark.domain.model.ChatRole
import com.unchunks.echomark.domain.provider.ApiProvider
import kotlinx.coroutines.flow.Flow

/** 呼び出しの用途。各社の推論の深さ(effort 等)や出力形式の切り替えに使う。 */
enum class ApiPurpose {
    /** 要約・タグ・カテゴリ(JSON で返させる) */
    ANALYZE,
    /** RAG チャット */
    CHAT,
    /** 接続テスト(短い応答) */
    CONNECTION_TEST
}

/**
 * 会話の1ターン。role は USER / ASSISTANT のみ。
 * @property attachments テキストと一緒に渡すファイル(ユーザーの発言のみ)。各社のリクエストでテキストより前に置く
 *   (画像・文書を先に置くと読み取りの精度が上がる)。提供元が受け付けない種類は送らない
 */
data class ApiMessage(
    val role: ChatRole,
    val text: String,
    val attachments: List<ApiAttachment> = emptyList()
)

/** 提供元に依存しないリクエスト。 */
data class ApiRequest(
    val purpose: ApiPurpose,
    val system: String,
    val messages: List<ApiMessage>
)

/** 呼び出しに使う認証情報とモデル。toString でキーを出さない。 */
class ApiCredentials(val apiKey: String, val model: String) {
    override fun toString(): String = "ApiCredentials(model=$model, apiKey=***)"
}

/**
 * 各社 API の最小限の呼び出し口。プロンプトの組み立ては [ApiLlmProvider] が行う。
 * 失敗は [com.unchunks.echomark.domain.provider.LlmException] の各型で投げる。
 */
interface ApiLlmClient {
    val provider: ApiProvider

    /** 応答全文を返す。 */
    suspend fun complete(request: ApiRequest, credentials: ApiCredentials): String

    /** 応答をテキストの増分で流す(SSE)。収集側のキャンセルで接続も閉じる。 */
    fun stream(request: ApiRequest, credentials: ApiCredentials): Flow<String>
}

/**
 * 同じロールの連続をまとめ、先頭のアシスタント発言を除く。
 * 送信失敗で「ユーザー発言だけ」が残った履歴でも、各社 API が受け付ける交互の並びにする。
 */
internal fun normalizeTurns(messages: List<ApiMessage>): List<ApiMessage> {
    val result = mutableListOf<ApiMessage>()
    for (message in messages) {
        if (message.text.isBlank() && message.attachments.isEmpty()) continue
        if (result.isEmpty() && message.role != ChatRole.USER) continue
        val last = result.lastOrNull()
        if (last != null && last.role == message.role) {
            result[result.lastIndex] = last.copy(
                text = listOf(last.text, message.text).filter { it.isNotBlank() }.joinToString("\n\n"),
                attachments = last.attachments + message.attachments
            )
        } else {
            result += message
        }
    }
    return result
}
