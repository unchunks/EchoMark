package com.unchunks.echomark.data.ai.api

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.http.StreamResponse
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.SseException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.errors.UnprocessableEntityException
import com.anthropic.models.ErrorType
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.model.ChatRole
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.provider.LlmException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Claude API(Messages API)。公式 Java SDK(anthropic-java)を使う。
 * - thinking は常時オン(Opus 5.5 は無効化できない)。深さは effort で調整する(要約 low / チャット medium)
 * - 拒否(stop_reason = refusal)に備え、対応モデルではサーバー側フォールバック(fallbacks: "default")を有効にする
 */
@Singleton
class ClaudeApiClient internal constructor(
    private val dispatcherProvider: DispatcherProvider,
    baseUrl: String?,
    maxRetries: Int,
    /** キーから SDK クライアントを作る(テストで差し替える) */
    private val clientFactory: (apiKey: String) -> AnthropicClient = { newClient(it, baseUrl, maxRetries) }
) : ApiLlmClient {

    @Inject
    constructor(dispatcherProvider: DispatcherProvider) : this(dispatcherProvider, null, DEFAULT_MAX_RETRIES)

    override val provider = ApiProvider.CLAUDE

    private val lock = Any()

    /** 保存済みのキーの SDK クライアント(内部に OkHttp の接続プールを持つ)を使い回す。[lock] で守る。 */
    private var cachedClient: Pair<String, AnthropicClient>? = null

    private fun clientFor(apiKey: String): AnthropicClient = synchronized(lock) {
        cachedClient?.let { (key, client) -> if (key == apiKey) return client }
        // キーが変わったら作り直す。古いクライアントは別の呼び出し(ストリーミング中のチャットなど)が
        // 使っている可能性があるため閉じない(OkHttp の接続・スレッドはアイドルになれば解放される)
        clientFactory(apiKey).also { cachedClient = apiKey to it }
    }

    override suspend fun complete(request: ApiRequest, credentials: ApiCredentials): String {
        val params = buildParams(request, credentials.model)
        val message: BetaMessage = callSdk(credentials.apiKey) {
            // SDK クライアントの生成(初回)は重いため IO で行う。
            // 非同期クライアントの Future は、コルーチンのキャンセルで取り消される
            withContext(dispatcherProvider.io) {
                if (request.purpose == ApiPurpose.CONNECTION_TEST) {
                    // 接続テストは入力中のキーを使うことがあるため、使い回すクライアントを置き換えない
                    val client = clientFactory(credentials.apiKey)
                    try {
                        client.async().beta().messages().create(params).await()
                    } finally {
                        client.close()
                    }
                } else {
                    clientFor(credentials.apiKey).async().beta().messages().create(params).await()
                }
            }
        }
        // 拒否時は content が空・不完全なことがあるため、stop_reason を先に確認する
        if (message.stopReason().orElse(null) == BetaStopReason.REFUSAL) {
            throw LlmException.Refused(refusalReason(message.stopDetails().flatMap { it.category() }.orElse(null)))
        }
        val text = message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
        if (text.isBlank() && request.purpose != ApiPurpose.CONNECTION_TEST) {
            throw LlmException.Unexpected("応答が空 (stop_reason=${message.stopReason().orElse(null)})")
        }
        return text
    }

    override fun stream(request: ApiRequest, credentials: ApiCredentials): Flow<String> = channelFlow {
        val params = buildParams(request, credentials.model)
        val responseRef = AtomicReference<StreamResponse<BetaRawMessageStreamEvent>?>(null)
        // 読み取りはブロッキングのため、収集側のキャンセル時はストリームを閉じて抜けさせる
        val canceller = launch {
            try {
                awaitCancellation()
            } finally {
                responseRef.get()?.close()
            }
        }
        try {
            callSdk(credentials.apiKey) {
                withContext(dispatcherProvider.io) {
                    val response = clientFor(credentials.apiKey).beta().messages().createStreaming(params)
                    responseRef.set(response)
                    response.use { r ->
                        val events = r.stream().iterator()
                        while (events.hasNext()) {
                            currentCoroutineContext().ensureActive()
                            handleEvent(events.next())?.let { send(it) }
                        }
                    }
                }
            }
        } finally {
            canceller.cancel()
        }
    }

    /** ストリームのイベントから、画面に流すテキストを取り出す。拒否なら例外。 */
    private fun handleEvent(event: BetaRawMessageStreamEvent): String? {
        event.contentBlockDelta().orElse(null)?.let { delta ->
            return delta.delta().text().orElse(null)?.text()?.takeIf { it.isNotEmpty() }
        }
        event.contentBlockStart().orElse(null)?.let { start ->
            // サーバー側フォールバックで別モデルが回答を引き継いだ境目。以降は新しい回答として続く
            if (start.contentBlock().isFallback()) return FALLBACK_SEPARATOR
        }
        event.messageDelta().orElse(null)?.let { delta ->
            if (delta.delta().stopReason().orElse(null) == BetaStopReason.REFUSAL) {
                // 途中までの出力は不完全なので、呼び出し側で破棄させる
                val category = delta.delta().stopDetails().flatMap { it.category() }.orElse(null)
                throw LlmException.Refused(refusalReason(category))
            }
        }
        return null
    }

    internal fun buildParams(request: ApiRequest, model: String): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens(request.purpose))
        if (request.system.isNotBlank()) builder.system(request.system)
        normalizeTurns(request.messages).forEach { turn ->
            if (turn.role == ChatRole.USER) builder.addUserMessage(turn.text) else builder.addAssistantMessage(turn.text)
        }
        if (supportsEffort(model)) {
            val effort = when (request.purpose) {
                ApiPurpose.CHAT -> BetaOutputConfig.Effort.MEDIUM
                ApiPurpose.ANALYZE, ApiPurpose.CONNECTION_TEST -> BetaOutputConfig.Effort.LOW
            }
            builder.outputConfig(BetaOutputConfig.builder().effort(effort).build())
        }
        if (supportsDefaultFallbacks(model)) {
            // 拒否カテゴリに応じて Anthropic 推奨の代替モデルでサーバー側が再実行する
            builder.addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01).fallbacksDefault()
        }
        return builder.build()
    }

    /** SDK の例外を [LlmException] に変換する。キャンセルは素通しする。 */
    private suspend fun <T> callSdk(apiKey: String, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: LlmException) {
        throw e
    } catch (e: AnthropicException) {
        currentCoroutineContext().ensureActive()
        throw mapSdkError(e, apiKey)
    }

    private fun refusalReason(category: Any?): String? = category?.toString()

    private fun maxTokens(purpose: ApiPurpose): Long = when (purpose) {
        // 思考トークンも max_tokens に含まれるため、低く設定しすぎない
        ApiPurpose.ANALYZE -> 16_000L
        ApiPurpose.CHAT -> 32_000L
        ApiPurpose.CONNECTION_TEST -> 2_000L
    }

    companion object {
        private const val DEFAULT_MAX_RETRIES = 2
        private const val TIMEOUT_SECONDS = 300L

        internal fun newClient(apiKey: String, baseUrl: String?, maxRetries: Int): AnthropicClient =
            AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .apply { if (baseUrl != null) baseUrl(baseUrl) }
                .maxRetries(maxRetries)
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build()
        private const val FALLBACK_SEPARATOR = "\n\n"

        /** Haiku 4.5 以前は effort 非対応(送ると 400)。 */
        internal fun supportsEffort(model: String): Boolean =
            model.startsWith("claude-") && !model.startsWith("claude-haiku") &&
                !model.contains("-4-5") && !model.startsWith("claude-3")

        /** fallbacks: "default" を受け付けるモデル。 */
        internal fun supportsDefaultFallbacks(model: String): Boolean = model in setOf(
            "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5", "claude-fable-5-1", "claude-fable-5"
        )

        internal fun mapSdkError(e: AnthropicException, apiKey: String): LlmException = when (e) {
            is UnauthorizedException -> LlmException.InvalidApiKey(401, e)
            is PermissionDeniedException -> LlmException.InvalidApiKey(403, e)
            is RateLimitException -> LlmException.RateLimited(
                e.headers().values("retry-after").firstOrNull()?.trim()?.toLongOrNull(), e
            )
            is InternalServerException -> LlmException.ServerError(e.statusCode(), e)
            is BadRequestException, is NotFoundException, is UnprocessableEntityException ->
                LlmException.BadRequest(
                    e.statusCode(),
                    e.message?.let { redactSecrets(it, apiKey) },
                    e
                )
            // ストリーム途中の error イベント(過負荷など)
            is SseException -> when (e.errorType().orElse(null)) {
                ErrorType.RATE_LIMIT_ERROR -> LlmException.RateLimited(null, e)
                ErrorType.AUTHENTICATION_ERROR, ErrorType.PERMISSION_ERROR -> LlmException.InvalidApiKey(401, e)
                ErrorType.INVALID_REQUEST_ERROR, ErrorType.NOT_FOUND_ERROR ->
                    LlmException.BadRequest(400, e.message?.let { redactSecrets(it, apiKey) }, e)
                else -> LlmException.ServerError(529, e)
            }
            is AnthropicServiceException -> when (val code = e.statusCode()) {
                401, 403 -> LlmException.InvalidApiKey(code, e)
                429 -> LlmException.RateLimited(null, e)
                in 500..599 -> LlmException.ServerError(code, e)
                in 400..499 -> LlmException.BadRequest(code, e.message?.let { redactSecrets(it, apiKey) }, e)
                else -> LlmException.Unexpected("HTTP $code", e)
            }
            is AnthropicIoException -> LlmException.Network(e)
            else -> LlmException.Unexpected(e.javaClass.simpleName, e)
        }
    }
}
