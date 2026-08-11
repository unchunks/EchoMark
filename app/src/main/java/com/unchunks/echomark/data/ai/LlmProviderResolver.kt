package com.unchunks.echomark.data.ai

import androidx.compose.ui.modifier.ModifierLocalProvider
import com.unchunks.echomark.di.qualifier.ApiAi
import com.unchunks.echomark.di.qualifier.LocalAi
import com.unchunks.echomark.domain.provider.LlmProvider
import javax.inject.Inject

class LlmProviderResolver @Inject constructor(
    @param:LocalAi private val localProvider: LlmProvider,
    @param:ApiAi private val apiProvider: LlmProvider
) {
    // TODO: 本来はユーザー設定(DataStore)から読み取る。設定画面が未実装のため、今はローカルAI固定
    fun resolve(): LlmProvider = localProvider
}