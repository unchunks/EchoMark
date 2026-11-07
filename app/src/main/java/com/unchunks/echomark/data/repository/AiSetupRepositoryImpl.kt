package com.unchunks.echomark.data.repository

import com.unchunks.echomark.data.ai.model.ModelManager
import com.unchunks.echomark.domain.repository.AiSetupRepository
import com.unchunks.echomark.domain.repository.AiSetupState
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.ApiKeyRepository
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.aiTaskSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

/** 設定・取り込み済みモデル・API キーの有無を組み合わせて、チャットで AI を使える状態かを流す。 */
class AiSetupRepositoryImpl @Inject constructor(
    appSettings: AppSettingsRepository,
    apiKeyRepository: ApiKeyRepository,
    modelManager: ModelManager
) : AiSetupRepository {

    override val setupState: Flow<AiSetupState> = combine(
        appSettings.aiTaskSetting(AiTask.CHAT),
        modelManager.installedModel,
        apiKeyRepository.configuredProviders
    ) { setting, model, configured ->
        AiSetupState.of(
            backend = setting.backend,
            isLocalModelInstalled = model != null,
            apiProvider = setting.apiProvider,
            configuredProviders = configured
        )
    }.distinctUntilChanged()
}
