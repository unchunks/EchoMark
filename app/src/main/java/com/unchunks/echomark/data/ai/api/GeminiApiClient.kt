package com.unchunks.echomark.data.ai.api

import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.model.ChatRole
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.provider.LlmException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gemini API(generateContent / streamGenerateContent, v1beta)。
 * https://ai.google.dev/api/generate-content
 * 会話状態をサーバーに残さないステートレスな generateContent を使う(Interactions API は使わない)。
 * キーは URL クエリではなく x-goog-api-key ヘッダで送る(URL がログに残ってもキーが漏れないように)。
 */
@Singleton
class GeminiApiClient internal constructor(
    private val okHttpClient: OkHttpClient,
    private val dispatcherProvider: DispatcherProvider,
    private val baseUrl: HttpUrl
) : ApiLlmClient {

    @Inject
    constructor(okHttpClient: OkHttpClient, dispatcherProvider: DispatcherProvider) :
        this(okHttpClient, dispatcherProvider, DEFAULT_BASE_URL.toHttpUrl())

    /**
     * LLM 用にタイムアウトを上書きしたクライアント(呼び出しのたびに作らない)。
     * 共通の [okHttpClient] と接続プール・ディスパッチャーを共有するため、接続(TLS 含む)は呼び出しをまたいで使い回される。
     */
    private val completeClient by lazy { okHttpClient.forLlm(streaming = false) }
    private val streamClient by lazy { okHttpClient.forLlm(streaming = true) }

    override val provider = ApiProvider.GEMINI

    override suspend fun complete(request: ApiRequest, credentials: ApiCredentials): String {
        val json = completeClient
            .postJson(buildRequest(request, credentials, stream = false), credentials.apiKey, dispatcherProvider.io)
        checkBlocked(json)
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw LlmException.Unexpected("候補が空")
        val text = candidate.extractText()
        if (text.isBlank() && candidate.optString("finishReason") in SAFETY_FINISH_REASONS) {
            throw LlmException.Refused(candidate.optString("finishReason"))
        }
        if (text.isBlank() && request.purpose != ApiPurpose.CONNECTION_TEST) {
            throw LlmException.Unexpected("応答が空 (${candidate.optString("finishReason")})")
        }
        return text
    }

    override fun stream(request: ApiRequest, credentials: ApiCredentials): Flow<String> =
        streamClient
            .sseEvents(buildRequest(request, credentials, stream = true), credentials.apiKey, dispatcherProvider.io)
            .transform { event ->
                val json = parseEventJson(event.data)
                json.optJSONObject("error")?.let {
                    throw httpError(it.optInt("code", 500), event.data, null, credentials.apiKey)
                }
                checkBlocked(json)
                val candidate = json.optJSONArray("candidates")?.optJSONObject(0) ?: return@transform
                val text = candidate.extractText()
                if (text.isNotEmpty()) emit(text)
                // 途中で安全フィルタに止められた場合、ここまでの出力は不完全なので破棄させる
                if (candidate.optString("finishReason") in SAFETY_FINISH_REASONS) {
                    throw LlmException.Refused(candidate.optString("finishReason"))
                }
            }
            .flowOn(dispatcherProvider.io)

    internal fun buildRequest(request: ApiRequest, credentials: ApiCredentials, stream: Boolean): Request {
        val method = if (stream) "streamGenerateContent" else "generateContent"
        val url = baseUrl.newBuilder()
            .addPathSegment("v1beta")
            .addPathSegment("models")
            .addPathSegment("${credentials.model}:$method")
            .apply { if (stream) addQueryParameter("alt", "sse") }
            .build()
        val contents = JSONArray()
        normalizeTurns(request.messages).forEach { turn ->
            val parts = JSONArray()
            // ファイル(画像・PDF・音声・動画)は inlineData で、テキストより前に置く
            // https://ai.google.dev/api/caching#Part
            turn.attachments.forEach { attachment ->
                parts.put(
                    JSONObject().put(
                        "inlineData",
                        JSONObject().put("mimeType", attachment.mimeType).put("data", attachment.base64Data)
                    )
                )
            }
            if (turn.text.isNotBlank() || turn.attachments.isEmpty()) parts.put(JSONObject().put("text", turn.text))
            contents.put(
                JSONObject()
                    .put("role", if (turn.role == ChatRole.USER) "user" else "model")
                    .put("parts", parts)
            )
        }
        val generationConfig = JSONObject().put("maxOutputTokens", maxOutputTokens(request.purpose))
        if (request.purpose == ApiPurpose.ANALYZE) generationConfig.put("responseMimeType", "application/json")
        if (request.purpose != ApiPurpose.CHAT && supportsThinkingLevel(credentials.model)) {
            // 要約・接続テストは深く考えさせない(Gemini 3 以降。thinkingBudget は 2.5 系の指定で、併用すると 400)
            generationConfig.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        }
        val body = JSONObject()
            .put("contents", contents)
            .put("generationConfig", generationConfig)
        if (request.system.isNotBlank()) {
            body.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", request.system)))
            )
        }
        return Request.Builder()
            .url(url)
            .header("x-goog-api-key", credentials.apiKey)
            .post(body.toJsonRequestBody())
            .build()
    }

    /** プロンプト自体がブロックされた(promptFeedback.blockReason)。 */
    private fun checkBlocked(json: JSONObject) {
        val reason = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
        if (reason.isNotEmpty() && reason != "BLOCK_REASON_UNSPECIFIED") throw LlmException.Refused(reason)
    }

    /** 思考の要約(thought=true)を除いたテキスト部分を連結する。 */
    private fun JSONObject.extractText(): String {
        val parts = optJSONObject("content")?.optJSONArray("parts") ?: return ""
        return buildString {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optBoolean("thought")) continue
                append(part.optString("text"))
            }
        }
    }

    private fun maxOutputTokens(purpose: ApiPurpose): Int = when (purpose) {
        // 思考モデルは思考分もこの上限に含まれるため、短い応答でも余裕を持たせる(要約は短いので、暴走を早めに止める)
        ApiPurpose.ANALYZE -> 6144
        ApiPurpose.CHAT -> 16384
        ApiPurpose.CONNECTION_TEST -> 1024
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/"
        private val GEMINI_MAJOR_VERSION = Regex("^gemini-(\\d+)")

        /**
         * thinkingConfig.thinkingLevel を受け付けるモデル(Gemini 3 以降)。
         * Gemini 2.5 系は thinkingBudget で指定する別の仕組みで、thinkingLevel を送ると 400 になる。
         */
        internal fun supportsThinkingLevel(model: String): Boolean =
            (GEMINI_MAJOR_VERSION.find(model.trim().lowercase())?.groupValues?.get(1)?.toIntOrNull() ?: 0) >= 3

        private val SAFETY_FINISH_REASONS = setOf(
            "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION", "IMAGE_SAFETY"
        )
    }
}
