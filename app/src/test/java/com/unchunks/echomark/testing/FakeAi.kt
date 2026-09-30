package com.unchunks.echomark.testing

import com.unchunks.echomark.data.security.SecretCipher
import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.repository.ApiKeyRepository
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.repository.RediscoverSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.security.GeneralSecurityException
import java.time.DayOfWeek

/** 設定の Fake。AI 関連だけを保持する。 */
class FakeAppSettingsRepository(
    backend: LlmBackend = LlmBackend.LOCAL,
    provider: ApiProvider = ApiProvider.CLAUDE
) : AppSettingsRepository {
    val backendFlow = MutableStateFlow(backend)
    val providerFlow = MutableStateFlow(provider)
    val modelsFlow = MutableStateFlow(ApiProvider.entries.associateWith { it.defaultModel })

    override val llmBackend: Flow<LlmBackend> = backendFlow
    override val apiProvider: Flow<ApiProvider> = providerFlow
    override val apiModels: Flow<Map<ApiProvider, String>> = modelsFlow
    override val rediscoverSettings: Flow<RediscoverSettings> = flowOf(RediscoverSettings())

    override suspend fun setLlmBackend(backend: LlmBackend) { backendFlow.value = backend }
    override suspend fun setApiProvider(provider: ApiProvider) { providerFlow.value = provider }
    override suspend fun setApiModel(provider: ApiProvider, modelId: String) {
        modelsFlow.value = modelsFlow.value + (provider to modelId.ifBlank { provider.defaultModel })
    }

    override suspend fun setRediscoverEnabled(enabled: Boolean) = TODO("not used")
    override suspend fun setRediscoverSchedule(dayOfWeek: DayOfWeek, hour: Int, minute: Int) = TODO("not used")
    override suspend fun getRediscoverNotified(): Map<Long, Long> = TODO("not used")
    override suspend fun recordRediscoverNotified(ids: List<Long>, notifiedAt: Long) = TODO("not used")
}

class FakeApiKeyRepository(initial: Map<ApiProvider, String> = emptyMap()) : ApiKeyRepository {
    private val keys = MutableStateFlow(initial)
    override val configuredProviders: Flow<Set<ApiProvider>> = keys.map { it.keys }
    override suspend fun getKey(provider: ApiProvider): String? = keys.value[provider]
    override suspend fun setKey(provider: ApiProvider, apiKey: String) { keys.value = keys.value + (provider to apiKey) }
    override suspend fun clearKey(provider: ApiProvider) { keys.value = keys.value - provider }
}

/** chatStream で [chunks] を順に流す LLM。[failure] があれば途中で投げる。 */
class FakeLlmProvider(
    var chunks: List<String> = listOf("こんにちは", "、世界"),
    var failure: Throwable? = null
) : LlmProvider {
    override suspend fun analyze(text: String): BookmarkAnalysis = BookmarkAnalysis("要約", emptyList(), "その他")

    override suspend fun chat(userMessage: String, context: List<String>, history: List<ChatMessage>): String =
        chunks.joinToString("")

    override fun chatStream(userMessage: String, context: List<String>, history: List<ChatMessage>): Flow<String> =
        flow {
            chunks.forEach { emit(it) }
            failure?.let { throw it }
        }
}

/** 可逆だが平文とは異なるバイト列にする暗号の Fake(Keystore は JVM テストで使えない)。 */
class FakeSecretCipher : SecretCipher {
    var failDecrypt = false

    override fun encrypt(plain: ByteArray): ByteArray = MAGIC + plain.map { (it.toInt() xor 0x5A).toByte() }

    override fun decrypt(data: ByteArray): ByteArray {
        if (failDecrypt || !data.take(MAGIC.size).toByteArray().contentEquals(MAGIC)) {
            throw GeneralSecurityException("cannot decrypt")
        }
        return data.drop(MAGIC.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    private companion object {
        val MAGIC = byteArrayOf(0x7F, 0x01)
    }
}
