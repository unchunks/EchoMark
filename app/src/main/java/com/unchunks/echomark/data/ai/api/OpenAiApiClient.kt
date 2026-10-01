package com.unchunks.echomark.data.ai.api

import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.model.ChatRole
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.provider.LlmException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.takeWhile
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
 * OpenAI Responses API(POST /v1/responses)。
 * https://developers.openai.com/api/docs/guides/text
 * store=false で応答をサーバー側に保存させない(会話履歴は毎回こちらから送る)。
 */
@Singleton
class OpenAiApiClient internal constructor(
    private val okHttpClient: OkHttpClient,
    private val dispatcherProvider: DispatcherProvider,
    private val baseUrl: HttpUrl
) : ApiLlmClient {

    @Inject
    constructor(okHttpClient: OkHttpClient, dispatcherProvider: DispatcherProvider) :
        this(okHttpClient, dispatcherProvider, DEFAULT_BASE_URL.toHttpUrl())

    override val provider = ApiProvider.OPENAI

    override suspend fun complete(request: ApiRequest, credentials: ApiCredentials): String {
        val json = okHttpClient.forLlm(streaming = false)
            .postJson(buildRequest(request, credentials, stream = false), credentials.apiKey, dispatcherProvider.io)
        val (text, refusal) = extractOutput(json)
        if (text.isBlank() && refusal != null) throw LlmException.Refused(refusal.take(200))
        val incompleteReason = json.optJSONObject("incomplete_details")?.optString("reason")
        if (incompleteReason == "content_filter") throw LlmException.Refused(incompleteReason)
        if (json.optString("status") == "failed") {
            throw LlmException.Unexpected(json.optJSONObject("error")?.optString("code"))
        }
        if (text.isBlank() && request.purpose != ApiPurpose.CONNECTION_TEST) {
            throw LlmException.Unexpected("応答が空 (${json.optString("status")} $incompleteReason)")
        }
        return text
    }

    override fun stream(request: ApiRequest, credentials: ApiCredentials): Flow<String> =
        okHttpClient.forLlm(streaming = true)
            .sseEvents(buildRequest(request, credentials, stream = true), credentials.apiKey, dispatcherProvider.io)
            .takeWhile { it.data != "[DONE]" }
            .transform { event ->
                val json = parseEventJson(event.data)
                when (json.optString("type").ifEmpty { event.event.orEmpty() }) {
                    "response.output_text.delta" -> json.optString("delta").takeIf { it.isNotEmpty() }?.let { emit(it) }
                    // 拒否はここまでの出力を破棄させる
                    "response.refusal.delta", "response.refusal.done" ->
                        throw LlmException.Refused(json.optString("refusal").ifEmpty { null })
                    "response.incomplete" -> {
                        val reason = json.optJSONObject("response")
                            ?.optJSONObject("incomplete_details")?.optString("reason")
                        if (reason == "content_filter") throw LlmException.Refused(reason)
                    }
                    "response.failed" -> {
                        val error = json.optJSONObject("response")?.optJSONObject("error")
                        throw LlmException.Unexpected(error?.optString("code")?.ifEmpty { null })
                    }
                    "error" -> throw streamError(json, credentials.apiKey)
                }
            }
            .flowOn(dispatcherProvider.io)

    internal fun buildRequest(request: ApiRequest, credentials: ApiCredentials, stream: Boolean): Request {
        val input = JSONArray()
        normalizeTurns(request.messages).forEach { turn ->
            input.put(
                JSONObject()
                    .put("role", if (turn.role == ChatRole.USER) "user" else "assistant")
                    .put("content", turn.text)
            )
        }
        val body = JSONObject()
            .put("model", credentials.model)
            .put("input", input)
            .put("store", false)
            .put("max_output_tokens", maxOutputTokens(request.purpose))
        if (request.system.isNotBlank()) body.put("instructions", request.system)
        if (request.purpose == ApiPurpose.ANALYZE) {
            // json_object はプロンプトに "JSON" の語が必要(分析プロンプトに含まれる)
            body.put("text", JSONObject().put("format", JSONObject().put("type", "json_object")))
        }
        if (stream) body.put("stream", true)
        return Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("v1").addPathSegment("responses").build())
            .header("Authorization", "Bearer ${credentials.apiKey}")
            .post(body.toJsonRequestBody())
            .build()
    }

    /** output[] の message から (テキスト, 拒否文) を取り出す。 */
    private fun extractOutput(json: JSONObject): Pair<String, String?> {
        val output = json.optJSONArray("output") ?: return "" to null
        val text = StringBuilder()
        var refusal: String? = null
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                when (part.optString("type")) {
                    "output_text" -> text.append(part.optString("text"))
                    "refusal" -> refusal = part.optString("refusal")
                }
            }
        }
        return text.toString() to refusal
    }

    /** ストリーム途中の error イベント。 */
    private fun streamError(json: JSONObject, apiKey: String): LlmException {
        val code = json.optString("code")
        return when {
            code.contains("rate_limit") -> LlmException.RateLimited()
            code.contains("invalid_api_key") -> LlmException.InvalidApiKey(401)
            code.contains("server_error") || code.isEmpty() -> LlmException.ServerError(500)
            else -> LlmException.Unexpected(redactSecrets(code, apiKey))
        }
    }

    private fun maxOutputTokens(purpose: ApiPurpose): Int = when (purpose) {
        // 推論モデルは推論トークンもこの上限に含まれるため、短い応答でも余裕を持たせる
        ApiPurpose.ANALYZE -> 8192
        ApiPurpose.CHAT -> 16384
        ApiPurpose.CONNECTION_TEST -> 1024
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/"
    }
}
