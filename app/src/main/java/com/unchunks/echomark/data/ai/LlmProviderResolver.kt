package com.unchunks.echomark.data.ai

import com.unchunks.echomark.di.qualifier.ApiAi
import com.unchunks.echomark.di.qualifier.LocalAi
import com.unchunks.echomark.domain.provider.LlmException
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.repository.ApiKeyRepository
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.LlmBackend
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** 設定(DataStore)の「AIの実行場所」に応じて [LlmProvider] を選ぶ。既定はローカル。 */
class LlmProviderResolver @Inject constructor(
    @param:LocalAi private val localProvider: LlmProvider,
    @param:ApiAi private val apiProvider: LlmProvider,
    private val appSettings: AppSettingsRepository,
    private val apiKeyRepository: ApiKeyRepository
) {
    /**
     * @throws LlmException.ApiKeyMissing クラウド API が選ばれているのに、選択中の提供元のキーが未設定のとき
     */
    suspend fun resolve(): LlmProvider = when (appSettings.llmBackend.first()) {
        LlmBackend.LOCAL -> localProvider
        LlmBackend.API -> {
            val provider = appSettings.apiProvider.first()
            if (provider !in apiKeyRepository.configuredProviders.first()) {
                throw LlmException.ApiKeyMissing(provider)
            }
            apiProvider
        }
    }
}
