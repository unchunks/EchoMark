package com.unchunks.echomark.data.websearch

import com.unchunks.echomark.domain.repository.ApiKeyRepository
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.websearch.WebSearchAvailability
import com.unchunks.echomark.domain.websearch.WebSearchStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

/** 保存したキーとオフラインモードの設定から、Web 検索を使えるかを決める。 */
class SettingsWebSearchStatus @Inject constructor(
    apiKeys: ApiKeyRepository,
    appSettings: AppSettingsRepository
) : WebSearchStatus {
    override val availability: Flow<WebSearchAvailability> =
        combine(apiKeys.webSearchKeyConfigured, appSettings.offlineMode, ::WebSearchAvailability).distinctUntilChanged()
}
