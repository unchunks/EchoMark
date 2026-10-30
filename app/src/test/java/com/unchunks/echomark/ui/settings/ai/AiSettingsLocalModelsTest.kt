package com.unchunks.echomark.ui.settings.ai

import com.unchunks.components.llm.ApiProvider
import com.unchunks.echomark.data.ai.model.InstalledModels
import com.unchunks.echomark.data.ai.model.LocalModelInfo
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.AiTaskSetting
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.aiTasks
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI 設定の、用途ごとの端末内モデルの状態。 */
class AiSettingsLocalModelsTest {

    private val gemma = LocalModelInfo("gemma.litertlm", "gemma.litertlm", 1, 0)
    private val qwen = LocalModelInfo("qwen.litertlm", "qwen.litertlm", 1, 0)
    private val installed = InstalledModels(listOf(gemma, qwen), defaultId = gemma.id)

    private fun state(vararg overrides: Pair<AiTask, AiTaskSetting>) =
        AiSettingsUiState(tasks = aiTasks(LlmBackend.LOCAL) + overrides, localModels = installed)

    private fun local(modelId: String?) = AiTaskSetting(localModelId = modelId)

    @Test
    fun 用途に選んだモデルを使い_選んでいない_削除したなら既定のモデルを使う() {
        val state = state(AiTask.CHAT to local(qwen.id), AiTask.SUMMARY to local("deleted"))

        assertEquals(qwen, state.localModelFor(AiTask.CHAT))
        assertEquals(gemma, state.localModelFor(AiTask.SUMMARY))
        assertEquals(gemma, state.localModelFor(AiTask.TAGGING))
    }

    @Test
    fun 端末内の用途が別々のモデルを使うときだけ読み込み直しを伝える() {
        assertFalse(state().usesMultipleLocalModels)
        assertTrue(state(AiTask.CHAT to local(qwen.id)).usesMultipleLocalModels)
        // 既定のモデルをわざわざ選んでも、同じモデルなら読み込み直さない
        assertFalse(state(AiTask.CHAT to local(gemma.id)).usesMultipleLocalModels)
        // クラウド API の用途に残っているモデルの選択は関係しない
        val api = AiTaskSetting(backend = LlmBackend.API, apiProvider = ApiProvider.CLAUDE, localModelId = qwen.id)
        assertFalse(state(AiTask.CHAT to api).usesMultipleLocalModels)
    }

    @Test
    fun モデルを削除したら選んでいた用途だけ既定のモデルに戻す() = runBlocking {
        val settings = FakeAppSettingsRepository()
        settings.setLocalModel(AiTask.CHAT, "qwen")
        settings.setLocalModel(AiTask.SUMMARY, "qwen")
        settings.setLocalModel(AiTask.TAGGING, "gemma")

        settings.resetTasksUsingLocalModel("qwen")

        val tasks = settings.aiTaskSettings.first()
        assertEquals(null, tasks.getValue(AiTask.CHAT).localModelId)
        assertEquals(null, tasks.getValue(AiTask.SUMMARY).localModelId)
        assertEquals("gemma", tasks.getValue(AiTask.TAGGING).localModelId)
    }
}
