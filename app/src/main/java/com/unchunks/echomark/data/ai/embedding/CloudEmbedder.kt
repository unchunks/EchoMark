package com.unchunks.echomark.data.ai.embedding

import com.unchunks.components.embedding.EmbeddingUnavailableException
import com.unchunks.components.llm.ApiProvider
import com.unchunks.components.llm.LlmException
import com.unchunks.components.llm.cloud.ApiCredentials
import com.unchunks.components.llm.cloud.ApiEmbeddingClient
import com.unchunks.components.llm.cloud.ApiTokenUsage
import com.unchunks.components.llm.cloud.EmbeddingPurpose
import com.unchunks.components.llm.cloud.GeminiEmbeddingClient
import com.unchunks.components.llm.cloud.OpenAiEmbeddingClient
import com.unchunks.components.web.NetworkGate
import com.unchunks.components.web.OfflineModeException
import com.unchunks.echomark.domain.provider.EmbeddingModels
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.ApiKeyRepository
import com.unchunks.echomark.domain.usage.ApiUsageRecord
import com.unchunks.echomark.domain.usage.ApiUsageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** クラウド API で埋め込みを作る。使えなければ(キー未設定・オフライン・通信の失敗など)[EmbeddingUnavailableException]。 */
fun interface CloudEmbedder {
    suspend fun embed(provider: ApiProvider, text: String, purpose: EmbeddingPurpose): FloatArray
}

/**
 * 各社の埋め込み API([ApiEmbeddingClient])で埋め込みを作る [CloudEmbedder]。
 * モデルと次元は提供元ごとに決まったもの([EmbeddingModels.cloudModelId]・[EmbeddingModels.CLOUD_DIMENSIONS])。
 *
 * 失敗はすべて [EmbeddingUnavailableException] にする。埋め込みを使う側は、これを「今は埋め込みを作れない」として扱い、
 * 要約・タグ付けは続け、検索はキーワードだけで動かす(埋め込みは次の起動や切り替えのときに作り直される)。
 * 保存用(全件の作り直しで続けて呼ぶ)の呼び出しは、回数の上限([LlmException.RateLimited])に当たったら少し待って
 * やり直す。検索の質問は待たせない。
 *
 * 使ったトークン数は、ほかのクラウド AI と同じく使用量に記録する。用途は、保存用はブックマークの処理(要約)、
 * 検索の質問はチャット・検索として記録する(用途の欄に埋め込みの区別は無い。集計は提供元とモデルごと)。
 */
@Singleton
class ApiCloudEmbedder internal constructor(
    clients: List<ApiEmbeddingClient>,
    private val apiKeyRepository: ApiKeyRepository,
    private val usageRepository: ApiUsageRepository,
    private val gate: NetworkGate,
    private val wait: suspend (Long) -> Unit
) : CloudEmbedder {

    @Inject
    constructor(
        openAi: OpenAiEmbeddingClient,
        gemini: GeminiEmbeddingClient,
        apiKeyRepository: ApiKeyRepository,
        usageRepository: ApiUsageRepository,
        gate: NetworkGate
    ) : this(listOf(openAi, gemini), apiKeyRepository, usageRepository, gate, { delay(it) })

    private val clients: Map<ApiProvider, ApiEmbeddingClient> = clients.associateBy { it.provider }

    override suspend fun embed(provider: ApiProvider, text: String, purpose: EmbeddingPurpose): FloatArray {
        // オフラインモードの間は送らない(共通の OkHttpClient でも断るが、呼ぶ前に止めて失敗を増やさない)
        if (gate.isOffline()) throw EmbeddingUnavailableException(OfflineModeException("クラウドの埋め込み"))
        val client = clients[provider]
        val model = EmbeddingModels.cloudModelId(provider)
        if (client == null || model == null) throw EmbeddingUnavailableException()
        val key = apiKeyRepository.getKey(provider) ?: throw EmbeddingUnavailableException(LlmException.ApiKeyMissing(provider))
        val credentials = ApiCredentials(key, model)
        var retries = 0
        while (true) {
            var usage: ApiTokenUsage? = null
            try {
                return client.embed(text, purpose, credentials, EmbeddingModels.CLOUD_DIMENSIONS) { usage = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException.RateLimited) {
                if (purpose == EmbeddingPurpose.QUERY || retries >= MAX_RATE_LIMIT_RETRIES) throw EmbeddingUnavailableException(e)
                retries++
                val waitMillis = ((e.retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS) * 1_000).coerceIn(1_000, MAX_WAIT_MILLIS)
                Timber.i("埋め込みの API の回数の上限に達したため %d ms 待ってやり直す(%d 回目)", waitMillis, retries)
                wait(waitMillis)
            } catch (e: LlmException) {
                throw EmbeddingUnavailableException(e)
            } finally {
                usage?.let { record(provider, model, purpose, it) }
            }
        }
    }

    private suspend fun record(provider: ApiProvider, model: String, purpose: EmbeddingPurpose, usage: ApiTokenUsage) {
        // 呼び出しのキャンセルで記録を落とさない。記録できなくても埋め込みの結果は変えない
        withContext(NonCancellable) {
            try {
                usageRepository.record(
                    ApiUsageRecord(
                        createdAt = System.currentTimeMillis(),
                        provider = provider,
                        model = model,
                        task = if (purpose == EmbeddingPurpose.QUERY) AiTask.CHAT else AiTask.SUMMARY,
                        inputTokens = usage.inputTokens,
                        outputTokens = usage.outputTokens
                    )
                )
            } catch (e: Exception) {
                Timber.w(e, "埋め込みの API の使用量を記録できない")
            }
        }
    }

    private companion object {
        /** 回数の上限に当たったときに待ってやり直す回数(保存用だけ) */
        const val MAX_RATE_LIMIT_RETRIES = 3

        /** 待つ時間が応答に無いときに待つ秒数 */
        const val DEFAULT_RETRY_AFTER_SECONDS = 20L

        /** 1回に待つ時間の上限(作り直しのワーカーを長く止めない) */
        const val MAX_WAIT_MILLIS = 60_000L
    }
}
