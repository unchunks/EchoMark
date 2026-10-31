package com.unchunks.echomark.data.extract.transcribe

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.llm.ApiProvider
import com.unchunks.components.speech.GeminiTranscriptionClient
import com.unchunks.components.speech.OnDeviceSpeechTranscriber
import com.unchunks.components.speech.OpenAiTranscriptionClient
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.FakeApiKeyRepository
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** クラウドに音声を送るのは、要約にクラウド API・送信の許可・音声に対応した提供元・キーがそろったときだけ。 */
@RunWith(AndroidJUnit4::class)
class TranscriptionProviderSelectorTest {

    private val settings = FakeAppSettingsRepository(backend = LlmBackend.API, provider = ApiProvider.OPENAI)
    private val keys = FakeApiKeyRepository(
        mapOf(ApiProvider.OPENAI to "sk-test", ApiProvider.GEMINI to "AIza-test", ApiProvider.CLAUDE to "sk-ant")
    )

    private fun selector(): TranscriptionProviderSelector {
        val dispatchers = TestDispatcherProvider(Dispatchers.Unconfined)
        return TranscriptionProviderSelector(
            settings,
            keys,
            OpenAiTranscriptionClient(OkHttpClient(), dispatchers),
            GeminiTranscriptionClient(OkHttpClient(), dispatchers),
            OnDeviceSpeechTranscriber(ApplicationProvider.getApplicationContext())
        )
    }

    private fun names() = runBlocking { selector().providers().map { it.name } }

    @Test
    fun 条件がそろえばクラウドの後に端末内() {
        assertEquals(listOf("openai", "on-device"), names())
        runBlocking { settings.setApiProvider(AiTask.SUMMARY, ApiProvider.GEMINI) }
        assertEquals(listOf("gemini", "on-device"), names())
    }

    @Test
    fun Claudeは音声に対応していないので端末内だけ() {
        runBlocking { settings.setApiProvider(AiTask.SUMMARY, ApiProvider.CLAUDE) }

        assertEquals(listOf("on-device"), names())
    }

    @Test
    fun ファイルの送信を許可していなければ端末内だけ() {
        settings.sendFilesToCloudFlow.value = false

        assertEquals(listOf("on-device"), names())
    }

    @Test
    fun チャットの音声はチャットの設定に合わせる() {
        // 要約はクラウドでも、チャットが端末内なら声をクラウドに送らない
        runBlocking { settings.setLlmBackend(AiTask.CHAT, LlmBackend.LOCAL) }
        assertEquals(listOf("on-device"), runBlocking { selector().providers(AiTask.CHAT).map { it.name } })
        assertEquals(listOf("openai", "on-device"), names())

        // チャットがクラウドでも、要約が端末内なら要約の文字起こしは送らない
        runBlocking {
            settings.setLlmBackend(AiTask.CHAT, LlmBackend.API)
            settings.setLlmBackend(AiTask.SUMMARY, LlmBackend.LOCAL)
        }
        assertEquals(listOf("openai", "on-device"), runBlocking { selector().providers(AiTask.CHAT).map { it.name } })
        assertEquals(listOf("on-device"), names())
    }

    @Test
    fun 端末内のAIを選んでいれば端末内だけ() {
        settings.setBackendForAll(LlmBackend.LOCAL)

        assertEquals(listOf("on-device"), names())
    }

    @Test
    fun 要約の設定に従い_ほかの用途の設定は見ない() = runBlocking {
        settings.setLlmBackend(AiTask.SUMMARY, LlmBackend.LOCAL)
        assertEquals(listOf("on-device"), names())

        settings.setLlmBackend(AiTask.SUMMARY, LlmBackend.API)
        settings.setLlmBackend(AiTask.TAGGING, LlmBackend.LOCAL)
        settings.setApiProvider(AiTask.CHAT, ApiProvider.CLAUDE)
        assertEquals(listOf("openai", "on-device"), names())
    }

    @Test
    fun キーが無ければ端末内だけ() = runBlocking {
        keys.clearKey(ApiProvider.OPENAI)

        assertEquals(listOf("on-device"), names())
    }
}
