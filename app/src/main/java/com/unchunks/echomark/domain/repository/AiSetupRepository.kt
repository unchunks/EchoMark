package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.provider.ApiProvider
import kotlinx.coroutines.flow.Flow

/** チャットで AI を使える状態か(「チャット」の用途の設定で判断する)。設定画面へ案内するかどうかの判断に使う。 */
enum class AiSetupState {
    /** 使える(チャットに選んだ実行場所に必要なものがそろっている)。 */
    READY,

    /** 端末内で実行する設定だが、モデルが未取り込み。 */
    LOCAL_MODEL_MISSING,

    /** クラウド API を使う設定だが、チャットに選んだ提供元の API キーが未設定。 */
    API_KEY_MISSING;

    val isReady: Boolean get() = this == READY

    companion object {
        /** 設定値から状態を決める(実際の推論に使う [com.unchunks.echomark.data.ai.LlmProviderResolver] と同じ規則)。 */
        fun of(
            backend: LlmBackend,
            isLocalModelInstalled: Boolean,
            apiProvider: ApiProvider,
            configuredProviders: Set<ApiProvider>
        ): AiSetupState = when (backend) {
            LlmBackend.LOCAL -> if (isLocalModelInstalled) READY else LOCAL_MODEL_MISSING
            LlmBackend.API -> if (apiProvider in configuredProviders) READY else API_KEY_MISSING
        }
    }
}

/** AI の準備状態を読むだけのリポジトリ(設定は書き換えない)。 */
interface AiSetupRepository {
    val setupState: Flow<AiSetupState>
}
