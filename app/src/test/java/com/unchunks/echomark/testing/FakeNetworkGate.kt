package com.unchunks.echomark.testing

import com.unchunks.echomark.data.network.NetworkGate
import com.unchunks.echomark.data.network.OfflineModeController
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** オフラインモードを [offline] で切り替えられる [NetworkGate]。既定はオフ(通信してよい) */
class FakeNetworkGate(offline: Boolean = false) : NetworkGate {
    val offlineFlow = MutableStateFlow(offline)

    var offline: Boolean
        get() = offlineFlow.value
        set(value) { offlineFlow.value = value }

    override fun isOffline(): Boolean = offlineFlow.value
    override val offlineMode: Flow<Boolean> = offlineFlow
}

/** 切り替えを記録し、設定にだけ書く [OfflineModeController] */
class FakeOfflineModeController(private val settings: AppSettingsRepository) : OfflineModeController {
    val calls = mutableListOf<Boolean>()

    override suspend fun setOfflineMode(enabled: Boolean) {
        calls += enabled
        settings.setOfflineMode(enabled)
    }
}
