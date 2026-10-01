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

    override val provider = ApiProvider.GEMINI

    override suspend fun complete(request: ApiRequest, credentials: ApiCredentials): String {
        val json = okHttpClient.forLlm(streaming = false)
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
        okHttpClient.forLlm(streaming = true)
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
            contents.put(
                JSONObject()
                    .put("role", if (turn.role == ChatRole.USER) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", turn.text)))
            )
        }
        val generationConfig = JSONObject().put("maxOutputTokens", maxOutputTokens(request.purpose))
        if (request.purpose == ApiPurpose.ANALYZE) generationConfig.put("responseMimeType", "application/json")
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
        // 思考モデルは思考分もこの上限に含まれるため、短い応答でも余裕を持たせる
        ApiPurpose.ANALYZE -> 8192
        ApiPurpose.CHAT -> 16384
        ApiPurpose.CONNECTION_TEST -> 1024
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/"
        private val SAFETY_FINISH_REASONS = setOf(
            "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION", "IMAGE_SAFETY"
        )
    }
}
