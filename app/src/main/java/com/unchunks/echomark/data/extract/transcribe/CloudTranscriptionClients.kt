package com.unchunks.echomark.data.extract.transcribe

import com.unchunks.echomark.data.ai.api.postJson
import com.unchunks.echomark.data.ai.api.toJsonRequestBody
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.provider.LlmException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音声のアップロードと文字起こしを待てるよう、タイムアウトを延ばしたクライアント。
 * (共通クライアントは callTimeout 15 秒。数 MB〜20MB の WAV を送り、数十秒〜数分の処理を待つ)
 */
private fun OkHttpClient.forTranscription(): OkHttpClient = newBuilder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS)
    .readTimeout(300, TimeUnit.SECONDS)
    .callTimeout(420, TimeUnit.SECONDS)
    .build()

private val WAV_MEDIA_TYPE = "audio/wav".toMediaType()

/**
 * OpenAI の音声の文字起こし(POST /v1/audio/transcriptions)。
 * https://developers.openai.com/api/docs/guides/speech-to-text
 * 1ファイル 25MB まで(16kHz モノラルの WAV なら約 13 分)。
 */
@Singleton
class OpenAiTranscriptionClient internal constructor(
    private val okHttpClient: OkHttpClient,
    private val dispatcherProvider: DispatcherProvider,
    private val baseUrl: HttpUrl
) {
    @Inject
    constructor(okHttpClient: OkHttpClient, dispatcherProvider: DispatcherProvider) :
        this(okHttpClient, dispatcherProvider, DEFAULT_BASE_URL.toHttpUrl())

    /**
     * WAV を文字に起こす。
     * @throws LlmException 通信・認証・レート制限などのエラー
     */
    suspend fun transcribe(wav: ByteArray, apiKey: String): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL)
            .addFormDataPart("file", "audio.wav", wav.toRequestBody(WAV_MEDIA_TYPE))
            .build()
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("v1/audio/transcriptions").build())
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        val json = okHttpClient.forTranscription().postJson(request, apiKey, dispatcherProvider.io)
        return json.optString("text")
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/"

        /** 文字起こしのモデル(2026-10 時点の公式ドキュメントで録音の文字起こしに推奨されているもの) */
        const val MODEL = "gpt-transcribe"
    }
}

/**
 * Gemini に音声を渡して文字起こしを頼む(generateContent。音声は inlineData で送る)。
 * https://ai.google.dev/gemini-api/docs/audio
 * リクエスト全体で 20MB まで(Base64 で 4/3 倍になるため、WAV は約 15MB = 16kHz モノラルで約 7 分まで)。
 */
@Singleton
class GeminiTranscriptionClient internal constructor(
    private val okHttpClient: OkHttpClient,
    private val dispatcherProvider: DispatcherProvider,
    private val baseUrl: HttpUrl
) {
    @Inject
    constructor(okHttpClient: OkHttpClient, dispatcherProvider: DispatcherProvider) :
        this(okHttpClient, dispatcherProvider, DEFAULT_BASE_URL.toHttpUrl())

    /**
     * WAV を文字に起こす。[model] は設定で選んでいる Gemini のモデル。
     * @throws LlmException 通信・認証・レート制限・安全フィルタなどのエラー
     */
    suspend fun transcribe(wav: ByteArray, apiKey: String, model: String): String {
        val parts = JSONArray()
            .put(JSONObject().put("text", PROMPT))
            .put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", "audio/wav")
                        .put("data", Base64.getEncoder().encodeToString(wav))
                )
            )
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("maxOutputTokens", MAX_OUTPUT_TOKENS))
        val request = Request.Builder()
            .url(
                baseUrl.newBuilder()
                    .addPathSegment("v1beta")
                    .addPathSegment("models")
                    .addPathSegment("$model:generateContent")
                    .build()
            )
            .header("x-goog-api-key", apiKey)
            .post(body.toJsonRequestBody())
            .build()
        val json = okHttpClient.forTranscription().postJson(request, apiKey, dispatcherProvider.io)
        json.optJSONObject("promptFeedback")?.optString("blockReason")
            ?.takeIf { it.isNotEmpty() && it != "BLOCK_REASON_UNSPECIFIED" }
            ?.let { throw LlmException.Refused(it) }
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw LlmException.Unexpected("候補が空")
        val text = buildString {
            val contentParts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            for (i in 0 until contentParts.length()) {
                val part = contentParts.optJSONObject(i) ?: continue
                if (part.optBoolean("thought")) continue
                append(part.optString("text"))
            }
        }
        val finishReason = candidate.optString("finishReason")
        if (text.isBlank() && finishReason in SAFETY_FINISH_REASONS) throw LlmException.Refused(finishReason)
        return text.trim().takeUnless { it == NO_SPEECH }.orEmpty()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/"

        /** 話していない音声のときに返させる印(本文に入れない) */
        private const val NO_SPEECH = "(発話なし)"

        private const val PROMPT = "この音声を文字に起こしてください。" +
            "話している言葉を、話された言語のまま、省略や要約をせずに書き出してください。" +
            "前置き・説明・タイムスタンプ・話者名は付けず、文字起こしの本文だけを出力してください。" +
            "話し声が無い場合は「$NO_SPEECH」とだけ出力してください。"

        /** 思考モデルは思考分もこの上限に含まれるため、7 分ほどの発話(数千字)に余裕を持たせる */
        private const val MAX_OUTPUT_TOKENS = 16384

        private val SAFETY_FINISH_REASONS = setOf(
            "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION"
        )
    }
}
