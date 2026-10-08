package com.unchunks.echomark.data.ai

import com.unchunks.echomark.data.ai.api.ApiLlmProviderFactory
import com.unchunks.echomark.di.qualifier.LocalAi
import com.unchunks.echomark.domain.provider.LlmException
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.ApiKeyRepository
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.repository.aiTaskSetting
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** 用途ごとの設定(DataStore)の「AIの実行場所」に応じて [LlmProvider] を選ぶ。既定はローカル。 */
class LlmProviderResolver @Inject constructor(
    @param:LocalAi private val localProvider: LlmProvider,
    private val apiProviders: ApiLlmProviderFactory,
    private val appSettings: AppSettingsRepository,
    private val apiKeyRepository: ApiKeyRepository
) {
    /**
     * @throws LlmException.ApiKeyMissing [task] にクラウド API が選ばれているのに、選択中の提供元のキーが未設定のとき
     */
    suspend fun resolve(task: AiTask): LlmProvider {
        val setting = appSettings.aiTaskSetting(task).first()
        return when (setting.backend) {
            LlmBackend.LOCAL -> localProvider
            LlmBackend.API -> {
                if (setting.apiProvider !in apiKeyRepository.configuredProviders.first()) {
                    throw LlmException.ApiKeyMissing(setting.apiProvider)
                }
                apiProviders.forTask(task)
            }
        }
    }
}
