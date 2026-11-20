package com.unchunks.echomark.data.repository

import com.unchunks.components.llm.ApiProvider
import com.unchunks.echomark.data.ai.model.InstalledModels
import com.unchunks.echomark.data.ai.model.LocalModelInfo
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.AiTaskSetting
import com.unchunks.echomark.domain.repository.ChatModelOption
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** チャットの AI の選択肢の作り方と、選んだときの設定の書き換え。 */
class ChatModelRepositoryImplTest {

    private val gemma = model("gemma", "Gemma 4.litertlm")
    private val qwen = model("qwen", "Qwen3.task")
    private val installed = InstalledModels(listOf(gemma, qwen), defaultId = "qwen")

    private fun model(id: String, name: String) = LocalModelInfo("$id.bin", name, 1L, 0L)

    private fun localOption(info: LocalModelInfo) = ChatModelOption.Local(info.id, info.displayName)

    @Test
    fun 選んでいなければ既定の端末内モデルが選択中になる() {
        val choices = chatModelChoices(AiTaskSetting(), installed, emptySet(), cloudEnabled = true)

        assertEquals(listOf(localOption(gemma), localOption(qwen)), choices.options)
        assertEquals(localOption(qwen), choices.selected)
    }

    @Test
    fun 選んだ端末内モデルが選択中になり_削除済みなら既定に戻る() {
        val picked = chatModelChoices(AiTaskSetting(localModelId = "gemma"), installed, emptySet(), cloudEnabled = true)
        assertEquals(localOption(gemma), picked.selected)

        val deleted = chatModelChoices(AiTaskSetting(localModelId = "gone"), installed, emptySet(), cloudEnabled = true)
        assertEquals(localOption(qwen), deleted.selected)
    }

    @Test
    fun モデルが無くキーも無ければ選択肢は空() {
        val choices = chatModelChoices(AiTaskSetting(), InstalledModels(), emptySet(), cloudEnabled = true)

        assertTrue(choices.options.isEmpty())
        assertNull(choices.selected)
    }

    @Test
    fun キーを設定した提供元のモデルだけを説明なしの名前で出す() {
        val choices = chatModelChoices(AiTaskSetting(), InstalledModels(), setOf(ApiProvider.GEMINI), cloudEnabled = true)

        val cloud = choices.options.filterIsInstance<ChatModelOption.Cloud>()
        assertTrue(cloud.all { it.provider == ApiProvider.GEMINI })
        assertEquals("Gemini 3.8 Flash", cloud.first { it.modelId == "gemini-3.8-flash" }.label)
    }

    @Test
    fun クラウドを無効にしている間はキーがあってもクラウドのモデルを出さない() {
        val choices = chatModelChoices(AiTaskSetting(), installed, setOf(ApiProvider.CLAUDE), cloudEnabled = false)

        assertTrue(choices.options.all { it is ChatModelOption.Local })
    }

    @Test
    fun AI設定で手入力したモデルも選択肢に出て選択中になる() {
        val setting = AiTaskSetting(
            backend = LlmBackend.API,
            apiProvider = ApiProvider.OPENAI,
            apiModels = mapOf(ApiProvider.OPENAI to "my-custom-model")
        )

        val choices = chatModelChoices(setting, installed, setOf(ApiProvider.OPENAI), cloudEnabled = true)

        assertEquals(ChatModelOption.Cloud(ApiProvider.OPENAI, "my-custom-model", "my-custom-model"), choices.selected)
    }

    @Test
    fun 選択中の提供元のキーが無ければ選択中は無し() {
        val setting = AiTaskSetting(backend = LlmBackend.API, apiProvider = ApiProvider.CLAUDE)

        assertNull(chatModelChoices(setting, installed, setOf(ApiProvider.GEMINI), cloudEnabled = true).selected)
    }

    @Test
    fun 端末内モデルを選ぶとチャットの用途だけが端末内のそのモデルになる() = runTest {
        val settings = FakeAppSettingsRepository(backend = LlmBackend.API)

        settings.selectChatModel(localOption(gemma))

        assertEquals(LlmBackend.LOCAL, settings.setting(AiTask.CHAT).backend)
        assertEquals("gemma", settings.setting(AiTask.CHAT).localModelId)
        assertEquals(LlmBackend.API, settings.setting(AiTask.SUMMARY).backend)
        assertNull(settings.setting(AiTask.SUMMARY).localModelId)
    }

    @Test
    fun クラウドのモデルを選ぶとチャットの用途の提供元とモデルが変わる() = runTest {
        val settings = FakeAppSettingsRepository()

        settings.selectChatModel(ChatModelOption.Cloud(ApiProvider.GEMINI, "gemini-3.1-pro-preview", "Gemini 3.1 Pro"))

        val chat = settings.setting(AiTask.CHAT)
        assertEquals(LlmBackend.API, chat.backend)
        assertEquals(ApiProvider.GEMINI, chat.apiProvider)
        assertEquals("gemini-3.1-pro-preview", chat.apiModel)
        assertFalse(settings.setting(AiTask.TAGGING).backend == LlmBackend.API)
    }
}
