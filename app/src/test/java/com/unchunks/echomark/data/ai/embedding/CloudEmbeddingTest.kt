package com.unchunks.echomark.data.ai.embedding

import com.unchunks.components.embedding.EmbeddingModelProfile
import com.unchunks.components.embedding.EmbeddingProvider
import com.unchunks.components.embedding.EmbeddingUnavailableException
import com.unchunks.components.llm.ApiProvider
import com.unchunks.components.llm.LlmException
import com.unchunks.components.llm.cloud.ApiCredentials
import com.unchunks.components.llm.cloud.ApiEmbeddingClient
import com.unchunks.components.llm.cloud.ApiTokenUsage
import com.unchunks.components.llm.cloud.EmbeddingPurpose
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.domain.bookmark.duplicates.duplicateDistanceScale
import com.unchunks.echomark.domain.provider.EmbeddingModels
import com.unchunks.echomark.domain.usage.ApiPricing
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.EMBEDDING_API_PROVIDERS
import com.unchunks.echomark.domain.repository.EmbeddingSetting
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.FakeApiKeyRepository
import com.unchunks.echomark.testing.FakeApiUsageRepository
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeNetworkGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** クラウドの埋め込み([ApiCloudEmbedder])と、設定に合わせた切り替え([SwitchingEmbeddingProvider])。 */
class CloudEmbeddingTest {

    /** 呼ばれた内容を覚え、[failures] を順に投げてから [vector] を返す埋め込みの API */
    private class FakeEmbeddingClient(
        override val provider: ApiProvider,
        private val failures: MutableList<Exception> = mutableListOf(),
        private val usage: ApiTokenUsage? = ApiTokenUsage(12, 0)
    ) : ApiEmbeddingClient {
        val calls = mutableListOf<Triple<String, EmbeddingPurpose, ApiCredentials>>()
        val dimensions = mutableListOf<Int>()
        override suspend fun embed(
            text: String,
            purpose: EmbeddingPurpose,
            credentials: ApiCredentials,
            dimensions: Int,
            onUsage: (ApiTokenUsage) -> Unit
        ): FloatArray {
            calls += Triple(text, purpose, credentials)
            this.dimensions += dimensions
            usage?.let(onUsage)
            failures.removeFirstOrNull()?.let { throw it }
            return FloatArray(dimensions) { if (it == 0) 1f else 0f }
        }
    }

    private val usage = FakeApiUsageRepository()
    private val gate = FakeNetworkGate()
    private val waits = mutableListOf<Long>()

    private fun embedder(
        vararg clients: ApiEmbeddingClient,
        keys: Map<ApiProvider, String> = mapOf(ApiProvider.GEMINI to "AIzaKeyForTest0000", ApiProvider.OPENAI to "sk-test-0000")
    ) = ApiCloudEmbedder(clients.toList(), FakeApiKeyRepository(keys), usage, gate) { waits += it }

    @Test
    fun 提供元ごとの決まったモデルと768次元で作り_使用量を用途つきで記録する() = runBlocking {
        val gemini = FakeEmbeddingClient(ApiProvider.GEMINI)
        val openAi = FakeEmbeddingClient(ApiProvider.OPENAI)
        val embedder = embedder(gemini, openAi)

        val vector = embedder.embed(ApiProvider.GEMINI, "猫の飼い方", EmbeddingPurpose.QUERY)
        embedder.embed(ApiProvider.OPENAI, "猫を飼う前に", EmbeddingPurpose.DOCUMENT)

        assertEquals(EmbeddingModels.CLOUD_DIMENSIONS, vector.size)
        assertEquals("gemini-embedding-2", gemini.calls.single().third.model)
        assertEquals("AIzaKeyForTest0000", gemini.calls.single().third.apiKey)
        assertEquals("text-embedding-3-small", openAi.calls.single().third.model)
        assertEquals(listOf(768), gemini.dimensions)
        // 検索の質問はチャット・検索、保存用はブックマークの処理として記録する
        val records = usage.records.value
        assertEquals(listOf(AiTask.CHAT, AiTask.SUMMARY), records.map { it.task })
        assertEquals(listOf("gemini-embedding-2", "text-embedding-3-small"), records.map { it.model })
        assertEquals(12L, records.first().inputTokens)
    }

    @Test
    fun キーが無い_オフライン_通信の失敗はEmbeddingUnavailableにし_オフラインでは呼ばない() = runBlocking {
        val gemini = FakeEmbeddingClient(ApiProvider.GEMINI, mutableListOf(LlmException.Network(null)))

        assertUnavailable { embedder(gemini, keys = emptyMap()).embed(ApiProvider.GEMINI, "文", EmbeddingPurpose.DOCUMENT) }
        assertUnavailable { embedder(gemini).embed(ApiProvider.GEMINI, "文", EmbeddingPurpose.DOCUMENT) }
        gate.offline = true
        assertUnavailable { embedder(gemini).embed(ApiProvider.GEMINI, "文", EmbeddingPurpose.DOCUMENT) }
        // 埋め込みの API が無い提供元
        gate.offline = false
        assertUnavailable { embedder(gemini).embed(ApiProvider.CLAUDE, "文", EmbeddingPurpose.DOCUMENT) }

        // 通信に失敗した1回だけ呼んだ(キーが無い・オフライン・Claude では呼ばない)
        assertEquals(1, gemini.calls.size)
    }

    @Test
    fun 保存用は回数の上限に当たったら待ってやり直し_検索の質問は待たせない() = runBlocking {
        val gemini = FakeEmbeddingClient(
            ApiProvider.GEMINI,
            mutableListOf(LlmException.RateLimited(retryAfterSeconds = 5), LlmException.RateLimited(retryAfterSeconds = null))
        )
        val embedder = embedder(gemini)

        embedder.embed(ApiProvider.GEMINI, "文", EmbeddingPurpose.DOCUMENT)
        assertEquals(listOf(5_000L, 20_000L), waits)
        assertEquals(3, gemini.calls.size)

        val limited = FakeEmbeddingClient(ApiProvider.GEMINI, mutableListOf(LlmException.RateLimited(retryAfterSeconds = 5)))
        assertUnavailable { embedder(limited).embed(ApiProvider.GEMINI, "質問", EmbeddingPurpose.QUERY) }
        assertEquals(1, limited.calls.size)
        assertEquals(2, waits.size)
    }

    @Test
    fun 設定に合わせて端末内とクラウドを切り替え_版としきい値も変わる() = runBlocking {
        val settings = FakeAppSettingsRepository()
        val cloudCalls = mutableListOf<Pair<ApiProvider, EmbeddingPurpose>>()
        val onDevice = RecordingOnDevice()
        var changes = 0
        val provider = SwitchingEmbeddingProvider(
            onDevice = onDevice,
            cloud = { p, _, purpose -> cloudCalls += p to purpose; FloatArray(768) },
            appSettings = settings,
            dispatcherProvider = TestDispatcherProvider(Dispatchers.Unconfined),
            onEngineChanged = { changes++ }
        )

        assertEquals(EmbeddingModels.BUNDLED.modelVersion, provider.modelVersion)
        provider.embedDocument("端末内")
        provider.prewarm()
        assertEquals(listOf("doc", "prewarm"), onDevice.calls)

        settings.setEmbeddingSetting(EmbeddingSetting(LlmBackend.API, ApiProvider.OPENAI))
        assertEquals(EmbeddingModels.CLOUD_OPENAI.modelVersion, provider.modelVersion)
        assertEquals(EmbeddingModels.CLOUD_OPENAI.minRagSimilarity, provider.profile.minRagSimilarity, 0.0)
        provider.embedQuery("質問")
        provider.prewarm()
        // クラウドでは端末内のモデルを読み込まない
        assertEquals(listOf("doc", "prewarm"), onDevice.calls)
        assertEquals(listOf(ApiProvider.OPENAI to EmbeddingPurpose.QUERY), cloudCalls)

        // クラウドの提供元を変える・端末内に戻すたびに作り直す。端末内のまま(使わない)提供元だけを変えても作り直さない
        settings.setEmbeddingSetting(EmbeddingSetting(LlmBackend.API, ApiProvider.GEMINI))
        settings.setEmbeddingSetting(EmbeddingSetting(LlmBackend.LOCAL, ApiProvider.OPENAI))
        settings.setEmbeddingSetting(EmbeddingSetting(LlmBackend.LOCAL, ApiProvider.GEMINI))
        assertEquals(3, changes)
        assertEquals(EmbeddingModels.BUNDLED.modelVersion, provider.modelVersion)
    }

    @Test
    fun 重複のしきい値は同梱のモデルなら変えず_ほかのモデルでは検索の上限の比で読み替える() {
        assertEquals(1.0, duplicateDistanceScale(EmbeddingModels.BUNDLED), 1e-9)
        assertEquals(1.5, duplicateDistanceScale(EmbeddingModels.CLOUD_OPENAI), 1e-9)
        assertTrue(duplicateDistanceScale(EmbeddingModels.CLOUD_GEMINI) < 1.0)
        assertNull(EmbeddingModels.cloudProfile(ApiProvider.CLAUDE))
    }

    @Test
    fun 選べる提供元にはどれも埋め込みのモデルと価格がある() {
        EMBEDDING_API_PROVIDERS.forEach { provider ->
            val model = EmbeddingModels.cloudModelId(provider)!!
            assertTrue("$provider $model の価格が表に無い", ApiPricing.hasPrice(provider, model))
            assertEquals(EmbeddingModels.CLOUD_DIMENSIONS, EmbeddingModels.cloudProfile(provider)!!.dimensions)
        }
    }

    /** 端末内の埋め込みの呼び出しを覚える */
    private class RecordingOnDevice : EmbeddingProvider {
        val calls = mutableListOf<String>()
        override val profile: EmbeddingModelProfile = EmbeddingModels.BUNDLED
        override suspend fun embedDocument(text: String): FloatArray = FloatArray(dimensions).also { calls += "doc" }
        override suspend fun embedQuery(text: String): FloatArray = FloatArray(dimensions).also { calls += "query" }
        override fun prewarm() { calls += "prewarm" }
    }

    private suspend fun assertUnavailable(block: suspend () -> Unit) {
        try {
            block()
            fail("EmbeddingUnavailableException になるはず")
        } catch (e: EmbeddingUnavailableException) {
            // 期待どおり
        }
    }
}
