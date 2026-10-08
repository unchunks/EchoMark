package com.unchunks.echomark.testing

import com.unchunks.echomark.data.security.SecretCipher
import com.unchunks.echomark.data.security.UnrecoverableSecretException
import com.unchunks.echomark.domain.model.AnalysisInput
import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.AiTaskSetting
import com.unchunks.echomark.domain.repository.ApiKeyRepository
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.repository.RediscoverSettings
import com.unchunks.echomark.domain.repository.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.DayOfWeek

/**
 * 設定の Fake。AI・表示・オンボーディング・再発見通知の設定を保持する。
 * AI の設定は、全用途を [backend]・[provider] で始める。
 */
class FakeAppSettingsRepository(
    backend: LlmBackend = LlmBackend.LOCAL,
    provider: ApiProvider = ApiProvider.CLAUDE
) : AppSettingsRepository {
    val tasksFlow = MutableStateFlow(aiTasks(backend, provider))

    override val aiTaskSettings: Flow<Map<AiTask, AiTaskSetting>> = tasksFlow

    fun setting(task: AiTask): AiTaskSetting = tasksFlow.value.getValue(task)

    /** 全用途の実行場所をまとめて変える。 */
    fun setBackendForAll(backend: LlmBackend) {
        tasksFlow.update { tasks -> tasks.mapValues { it.value.copy(backend = backend) } }
    }

    private fun updateTask(task: AiTask, transform: AiTaskSetting.() -> AiTaskSetting) {
        tasksFlow.update { it + (task to it.getValue(task).transform()) }
    }
    val rediscoverFlow = MutableStateFlow(RediscoverSettings())
    val themeModeFlow = MutableStateFlow(ThemeMode.SYSTEM)
    val dynamicColorFlow = MutableStateFlow(false)
    val onboardingCompletedFlow = MutableStateFlow(false)
    val sendFilesToCloudFlow = MutableStateFlow(true)
    var resetCalls = 0

    override val rediscoverSettings: Flow<RediscoverSettings> = rediscoverFlow
    override val themeMode: Flow<ThemeMode> = themeModeFlow
    override val dynamicColor: Flow<Boolean> = dynamicColorFlow
    override val onboardingCompleted: Flow<Boolean> = onboardingCompletedFlow
    override val sendFilesToCloud: Flow<Boolean> = sendFilesToCloudFlow

    override suspend fun setLlmBackend(task: AiTask, backend: LlmBackend) = updateTask(task) { copy(backend = backend) }
    override suspend fun setApiProvider(task: AiTask, provider: ApiProvider) = updateTask(task) { copy(apiProvider = provider) }
    override suspend fun setApiModel(task: AiTask, provider: ApiProvider, modelId: String) = updateTask(task) {
        copy(apiModels = apiModels + (provider to modelId.trim().ifBlank { provider.defaultModel }))
    }

    override suspend fun setThemeMode(mode: ThemeMode) { themeModeFlow.value = mode }
    override suspend fun setDynamicColor(enabled: Boolean) { dynamicColorFlow.value = enabled }
    override suspend fun setOnboardingCompleted(completed: Boolean) { onboardingCompletedFlow.value = completed }
    override suspend fun setSendFilesToCloud(enabled: Boolean) { sendFilesToCloudFlow.value = enabled }
    override suspend fun setRediscoverEnabled(enabled: Boolean) {
        rediscoverFlow.value = rediscoverFlow.value.copy(enabled = enabled)
    }
    override suspend fun setRediscoverSchedule(dayOfWeek: DayOfWeek, hour: Int, minute: Int) {
        rediscoverFlow.value = rediscoverFlow.value.copy(dayOfWeek = dayOfWeek, hour = hour, minute = minute)
    }
    override suspend fun resetToDefaults() {
        resetCalls++
        tasksFlow.value = aiTasks(LlmBackend.LOCAL, ApiProvider.CLAUDE)
        rediscoverFlow.value = RediscoverSettings()
        themeModeFlow.value = ThemeMode.SYSTEM
        dynamicColorFlow.value = false
        sendFilesToCloudFlow.value = true
    }
    override suspend fun getRediscoverNotified(): Map<Long, Long> = TODO("not used")
    override suspend fun recordRediscoverNotified(ids: List<Long>, notifiedAt: Long) = TODO("not used")
}

/** 全用途で同じ実行場所・提供元(モデルは各提供元の既定)の AI の設定。 */
fun aiTasks(backend: LlmBackend, provider: ApiProvider = ApiProvider.CLAUDE): Map<AiTask, AiTaskSetting> =
    AiTask.entries.associateWith {
        AiTaskSetting(backend, provider, ApiProvider.entries.associateWith { it.defaultModel })
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
    override suspend fun analyze(input: AnalysisInput, existingTags: List<String>, scope: AnalysisScope): BookmarkAnalysis =
        BookmarkAnalysis("要約", emptyList(), "その他")

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
    /** true なら鍵が失われたときと同じく、復号できない([UnrecoverableSecretException])。 */
    var failDecrypt = false

    /** 復号で投げる例外(Keystore の一時的なエラーなどの再現用)。 */
    var decryptFailure: Exception? = null

    override fun encrypt(plain: ByteArray): ByteArray = MAGIC + plain.map { (it.toInt() xor 0x5A).toByte() }

    override fun decrypt(data: ByteArray): ByteArray {
        decryptFailure?.let { throw it }
        if (failDecrypt || !data.take(MAGIC.size).toByteArray().contentEquals(MAGIC)) {
            throw UnrecoverableSecretException("cannot decrypt")
        }
        return data.drop(MAGIC.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    private companion object {
        val MAGIC = byteArrayOf(0x7F, 0x01)
    }
}
