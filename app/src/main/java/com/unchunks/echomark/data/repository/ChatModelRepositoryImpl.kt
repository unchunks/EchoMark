package com.unchunks.echomark.data.repository

import com.unchunks.components.llm.ApiProvider
import com.unchunks.echomark.data.ai.model.InstalledModels
import com.unchunks.echomark.data.ai.model.ModelManager
import com.unchunks.echomark.domain.provider.presets
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.AiTaskSetting
import com.unchunks.echomark.domain.repository.ApiKeyRepository
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.CLOUD_AI_ENABLED
import com.unchunks.echomark.domain.repository.ChatModelChoices
import com.unchunks.echomark.domain.repository.ChatModelOption
import com.unchunks.echomark.domain.repository.ChatModelRepository
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.repository.aiTaskSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

/** 取り込み済みの端末内モデルと、キーを設定した提供元のモデルを、チャットの選択肢にする。 */
class ChatModelRepositoryImpl @Inject constructor(
    private val appSettings: AppSettingsRepository,
    apiKeyRepository: ApiKeyRepository,
    modelManager: ModelManager
) : ChatModelRepository {

    override val choices: Flow<ChatModelChoices> = combine(
        appSettings.aiTaskSetting(AiTask.CHAT),
        modelManager.installed,
        apiKeyRepository.configuredProviders
    ) { setting, installed, configured ->
        chatModelChoices(setting, installed, configured, CLOUD_AI_ENABLED)
    }.distinctUntilChanged()

    override suspend fun select(option: ChatModelOption) = appSettings.selectChatModel(option)
}

/**
 * チャットの選択肢を作る(実際の推論に使う [com.unchunks.echomark.data.ai.LlmProviderResolver] と同じ規則で、選択中のものを決める)。
 * @param cloudEnabled false ならクラウドのモデルを出さない([CLOUD_AI_ENABLED])
 */
internal fun chatModelChoices(
    setting: AiTaskSetting,
    installed: InstalledModels,
    configuredProviders: Set<ApiProvider>,
    cloudEnabled: Boolean
): ChatModelChoices {
    val local = installed.models.map { ChatModelOption.Local(it.id, it.displayName) }
    val cloud = if (cloudEnabled) ApiProvider.entries.filter { it in configuredProviders }.flatMap { cloudOptions(it, setting) } else emptyList()
    val selected: ChatModelOption? = when (setting.backend) {
        LlmBackend.LOCAL -> installed.resolve(setting.localModelId)?.let { model -> local.firstOrNull { it.modelId == model.id } }
        LlmBackend.API -> cloud.firstOrNull { it.provider == setting.apiProvider && it.modelId == setting.apiModel }
    }
    return ChatModelChoices(local + cloud, selected)
}

/** [provider] のプリセットのモデルと、AI 設定で手入力したモデル。 */
private fun cloudOptions(provider: ApiProvider, setting: AiTaskSetting): List<ChatModelOption.Cloud> {
    val presets = provider.presets.map { ChatModelOption.Cloud(provider, it.id, it.label.substringBefore(PRESET_NOTE_START)) }
    val saved = setting.apiModels[provider]?.takeIf { it.isNotBlank() } ?: provider.defaultModel
    return if (presets.any { it.modelId == saved }) presets else presets + ChatModelOption.Cloud(provider, saved, saved)
}

/** プリセットの表示名の、説明が始まる印(「Claude Sonnet 5.5(バランス)」の「(」) */
private const val PRESET_NOTE_START = "("

/** 「チャット」の用途の設定を [option] に変える。実行場所を最後に変え、途中の状態で別のモデルが選ばれないようにする。 */
internal suspend fun AppSettingsRepository.selectChatModel(option: ChatModelOption) {
    when (option) {
        is ChatModelOption.Local -> {
            setLocalModel(AiTask.CHAT, option.modelId)
            setLlmBackend(AiTask.CHAT, LlmBackend.LOCAL)
        }
        is ChatModelOption.Cloud -> {
            setApiModel(AiTask.CHAT, option.provider, option.modelId)
            setApiProvider(AiTask.CHAT, option.provider)
            setLlmBackend(AiTask.CHAT, LlmBackend.API)
        }
    }
}
