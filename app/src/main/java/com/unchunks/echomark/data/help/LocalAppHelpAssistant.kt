package com.unchunks.echomark.data.help

import com.unchunks.echomark.data.ai.LocalLlmProviders
import com.unchunks.echomark.domain.help.AppGuideRetriever
import com.unchunks.echomark.domain.help.AppHelpAssistant
import com.unchunks.echomark.domain.help.GuideSection
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.aiTaskSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/**
 * 使い方の質問に、端末内 AI だけで答える。
 * チャットの設定がクラウド API でも端末内で動かす(使い方の案内はアプリの中だけで完結させたいのと、
 * 案内の指示は端末内 AI の短い文脈に合わせて作ってあるため)。モデルはチャットの用途で選んだもの。
 */
class LocalAppHelpAssistant @Inject constructor(
    private val localProviders: LocalLlmProviders,
    private val appSettings: AppSettingsRepository
) : AppHelpAssistant {

    override fun answer(question: String, guide: List<GuideSection>, history: List<ChatMessage>): Flow<String> = flow {
        val modelId = appSettings.aiTaskSetting(AiTask.CHAT).first().localModelId
        emitAll(localProviders.forModel(modelId).appHelpStream(question, AppGuideRetriever.format(guide), history))
    }
}
