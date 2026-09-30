package com.unchunks.echomark.data.ai

import com.unchunks.echomark.di.qualifier.ApiAi
import com.unchunks.echomark.di.qualifier.LocalAi
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.LlmBackend
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** 設定(DataStore)の「AIの実行場所」に応じて [LlmProvider] を選ぶ。既定はローカル。 */
class LlmProviderResolver @Inject constructor(
    @param:LocalAi private val localProvider: LlmProvider,
    @param:ApiAi private val apiProvider: LlmProvider,
    private val appSettings: AppSettingsRepository
) {
    suspend fun resolve(): LlmProvider = when (appSettings.llmBackend.first()) {
        LlmBackend.LOCAL -> localProvider
        LlmBackend.API -> apiProvider
    }
}
