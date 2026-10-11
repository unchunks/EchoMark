package com.unchunks.echomark.data.ai.api

import com.unchunks.echomark.domain.provider.LlmException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Server-Sent Events の1イベント。 */
internal data class SseEvent(val event: String?, val data: String)

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

internal fun JSONObject.toJsonRequestBody(): RequestBody = toString().toRequestBody(JSON_MEDIA_TYPE)

/**
 * LLM 呼び出し用にタイムアウトを上書きしたクライアント。
 * 共通クライアントは callTimeout 15 秒のため、推論待ちの長い応答に合わせて延ばす。
 */
internal fun OkHttpClient.forLlm(streaming: Boolean): OkHttpClient = newBuilder()
    .connectTimeout(15, TimeUnit.SECONDS)
    // ストリーミングは全体時間を制限せず、無通信の時間だけで打ち切る
    .callTimeout(if (streaming) 0L else 180L, TimeUnit.SECONDS)
    .readTimeout(if (streaming) 90L else 180L, TimeUnit.SECONDS)
    .build()

/** コルーチンのキャンセルで HTTP 呼び出しも取り消す。 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }
    })
}

/** JSON を POST し、成功時の JSON を返す。失敗は [LlmException] に変換する。 */
internal suspend fun OkHttpClient.postJson(
    request: Request,
    apiKey: String,
    ioContext: CoroutineContext
): JSONObject {
    val response = try {
        newCall(request).await()
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        throw LlmException.Network(e)
    }
    return response.use { r ->
        val body = withContext(ioContext) {
            try {
                r.body.string()
            } catch (e: IOException) {
                throw LlmException.Network(e)
            }
        }
        if (!r.isSuccessful) throw httpError(r.code, body, r.header("retry-after"), apiKey)
        try {
            JSONObject(body)
        } catch (e: Exception) {
            throw LlmException.Unexpected("JSON ではない応答", e)
        }
    }
}

/**
 * SSE の各イベントを流す。読み取りはブロッキングのため、収集側がキャンセルしたら
 * HTTP 呼び出しを取り消して読み取りを抜けさせる。
 */
internal fun OkHttpClient.sseEvents(
    request: Request,
    apiKey: String,
    ioContext: CoroutineContext
): Flow<SseEvent> = channelFlow {
    val call = newCall(request)
    val canceller = launch {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    try {
        val response = try {
            call.await()
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw LlmException.Network(e)
        }
        response.use { r ->
            withContext(ioContext) {
                if (!r.isSuccessful) {
                    val body = runCatching { r.body.string() }.getOrDefault("")
                    throw httpError(r.code, body, r.header("retry-after"), apiKey)
                }
                try {
                    readSse(r, this@channelFlow)
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw LlmException.Network(e)
                }
            }
        }
    } finally {
        canceller.cancel()
    }
}

private suspend fun readSse(response: Response, scope: ProducerScope<SseEvent>) {
    val source = response.body.source()
    var eventName: String? = null
    val data = StringBuilder()
    suspend fun dispatch() {
        if (data.isNotEmpty()) scope.send(SseEvent(eventName, data.toString()))
        eventName = null
        data.setLength(0)
    }
    while (true) {
        currentCoroutineContext().ensureActive()
        val line = source.readUtf8Line() ?: break
        when {
            line.isEmpty() -> dispatch()
            line.startsWith(":") -> Unit // コメント(keep-alive)
            line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
            }
        }
    }
    dispatch()
}

/** HTTP エラーを [LlmException] に変換する。本文の詳細はキーを伏せてから使う。 */
internal fun httpError(code: Int, body: String, retryAfter: String?, apiKey: String): LlmException {
    val detail = extractErrorMessage(body)?.let { redactSecrets(it, apiKey) }
    return when {
        code == 401 || code == 403 -> LlmException.InvalidApiKey(code)
        // Gemini はキー不正を 400 INVALID_ARGUMENT(API_KEY_INVALID)で返す
        code == 400 && body.contains("API_KEY_INVALID") -> LlmException.InvalidApiKey(code)
        code == 429 -> LlmException.RateLimited(retryAfter?.trim()?.toLongOrNull())
        code >= 500 -> LlmException.ServerError(code)
        code in 400..499 -> LlmException.BadRequest(code, detail)
        else -> LlmException.Unexpected("HTTP $code")
    }
}

/** `{"error": {"message": "..."}}` 形式(各社共通)からメッセージを取り出す。 */
private fun extractErrorMessage(body: String): String? = try {
    val json = JSONObject(body)
    val error: Any? = json.opt("error")
    val message = if (error is JSONObject) error.optString("message") else json.optString("message")
    message.takeIf { it.isNotBlank() } ?: (error as? String)
} catch (e: Exception) {
    null
}

private val SECRET_PATTERNS = listOf(
    Regex("sk-[A-Za-z0-9_\\-*.]{6,}"),
    Regex("AIza[0-9A-Za-z_\\-]{10,}")
)

/** エラーメッセージに含まれうる API キー(一部マスク済みのものも)を伏せる。 */
internal fun redactSecrets(text: String, apiKey: String): String {
    var result = if (apiKey.isNotEmpty()) text.replace(apiKey, "***") else text
    SECRET_PATTERNS.forEach { result = it.replace(result, "***") }
    return result
}

/** SSE の data を JSON として読む。壊れていれば [LlmException.Unexpected]。 */
internal fun parseEventJson(data: String): JSONObject = try {
    JSONObject(data)
} catch (e: Exception) {
    throw LlmException.Unexpected("ストリームの形式が不正", e)
}

/** キャンセルは素通しし、それ以外の想定外の例外を [LlmException] に包む。 */
internal inline fun <T> wrapUnexpected(block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: LlmException) {
    throw e
} catch (e: Exception) {
    throw LlmException.Unexpected(e.javaClass.simpleName, e)
}
