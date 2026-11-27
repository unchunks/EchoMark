package com.unchunks.echomark.data.ai.embedding

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.components.embedding.EmbeddingModelProfile
import com.unchunks.components.embedding.EmbeddingProvider
import com.unchunks.components.llm.ApiProvider
import com.unchunks.components.llm.cloud.EmbeddingPurpose
import com.unchunks.echomark.domain.provider.EmbeddingModels
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.EmbeddingSetting
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * 設定([AppSettingsRepository.embeddingSetting])に合わせて、端末内の埋め込みモデル([onDevice])か
 * クラウド API([cloud])で埋め込みを作る [EmbeddingProvider]。
 *
 * [profile](とそこから決まるベクトルの版)は同期で読まれるため、設定を覚えておき、変わったら書き換える。
 * まだ一度も読んでいないとき(起動直後)は、その場で設定を読んでから答える(違う版を返して、端末内のベクトルを
 * クラウドの版として保存しないように)。
 *
 * 作る場所(端末内・提供元)が変わったら、ベクトルの版が変わるので [onEngineChanged] を呼ぶ(全件の作り直しを積む)。
 * 起動して最初に読んだ値では呼ばない(起動時の作り直しは別に積んである)。
 */
class SwitchingEmbeddingProvider(
    private val onDevice: EmbeddingProvider,
    private val cloud: CloudEmbedder,
    private val appSettings: AppSettingsRepository,
    dispatcherProvider: DispatcherProvider,
    private val onEngineChanged: () -> Unit
) : EmbeddingProvider {

    @Volatile
    private var current: EmbeddingSetting? = null

    private val scope = CoroutineScope(
        SupervisorJob() + dispatcherProvider.io +
            CoroutineExceptionHandler { _, e -> Timber.e(e, "埋め込みの設定を読めない") }
    )

    init {
        scope.launch {
            appSettings.embeddingSetting.collect { setting ->
                val previous = current
                current = setting
                if (previous != null && previous.cloudProvider != setting.cloudProvider) {
                    Timber.i("埋め込みを作る場所が変わった: %s → %s", previous.cloudProvider ?: "端末内", setting.cloudProvider ?: "端末内")
                    onEngineChanged()
                }
            }
        }
    }

    private fun setting(): EmbeddingSetting =
        current ?: runBlocking { appSettings.embeddingSetting.first() }.also { if (current == null) current = it }

    /** いま埋め込みを作るクラウドの提供元。端末内なら null */
    private fun cloudProvider(): ApiProvider? =
        setting().cloudProvider?.takeIf { EmbeddingModels.cloudProfile(it) != null }

    override val profile: EmbeddingModelProfile
        get() = cloudProvider()?.let(EmbeddingModels::cloudProfile) ?: onDevice.profile

    override suspend fun embedDocument(text: String): FloatArray {
        val provider = cloudProvider() ?: return onDevice.embedDocument(text)
        return cloud.embed(provider, text, EmbeddingPurpose.DOCUMENT)
    }

    override suspend fun embedQuery(text: String): FloatArray {
        val provider = cloudProvider() ?: return onDevice.embedQuery(text)
        return cloud.embed(provider, text, EmbeddingPurpose.QUERY)
    }

    // クラウドは読み込みが要らない。端末内のモデルを読み込むと、使わないのにメモリを使う
    override fun prewarm() {
        if (cloudProvider() == null) onDevice.prewarm()
    }
}
