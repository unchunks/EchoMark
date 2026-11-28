package com.unchunks.echomark.data.help

import com.unchunks.components.llm.ApiProvider
import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.domain.help.AppActionSpec
import com.unchunks.echomark.domain.help.AppGuideRetriever
import com.unchunks.echomark.domain.help.AppHelpAssistant
import com.unchunks.echomark.domain.help.GuideSection
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.repository.aiTaskSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * 使い方の質問に、チャットの用途で選んだ AI で答える。端末内ならチャットで選んだ端末内モデル、
 * クラウド API ならチャットで選んだ提供元とモデル([LlmProviderResolver])。
 * 使い方のためだけに AI を選ばせないのと、チャットをクラウドにしている人は端末内モデルを取り込んでいないことがあるため。
 */
class LlmAppHelpAssistant @Inject constructor(
    private val resolver: LlmProviderResolver,
    private val appSettings: AppSettingsRepository
) : AppHelpAssistant {

    override val cloudProvider: Flow<ApiProvider?> =
        appSettings.aiTaskSetting(AiTask.CHAT)
            .map { setting -> setting.apiProvider.takeIf { setting.backend == LlmBackend.API } }
            .distinctUntilChanged()

    override fun answer(
        question: String,
        guide: List<GuideSection>,
        actions: List<AppActionSpec>,
        history: List<ChatMessage>
    ): Flow<String> = flow {
        emitAll(
            resolver.resolve(AiTask.CHAT)
                .appHelpStream(question, AppGuideRetriever.format(guide), actions.map { it.promptLine }, history)
        )
    }
}
