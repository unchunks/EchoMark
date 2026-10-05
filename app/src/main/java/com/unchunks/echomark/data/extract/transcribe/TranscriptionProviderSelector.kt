package com.unchunks.echomark.data.extract.transcribe

import com.unchunks.echomark.data.extract.media.PcmChunk
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.repository.ApiKeyRepository
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.LlmBackend
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * 文字起こしに使う仕組みを、試す順に選ぶ。
 *
 * クラウドに音声を送るのは、次のすべてを満たすときだけ:
 * - AI の実行場所がクラウド API([LlmBackend.API])
 * - 「ファイルをクラウドに送る」設定([AppSettingsRepository.sendFilesToCloud])がオン
 * - 選択中の提供元が音声を扱える(OpenAI・Gemini。Claude は音声入力に対応していない)で、その API キーがある
 *
 * クラウドで失敗したら端末内の認識に切り替える([Transcriber])。
 */
class TranscriptionProviderSelector @Inject constructor(
    private val appSettings: AppSettingsRepository,
    private val apiKeyRepository: ApiKeyRepository,
    private val openAi: OpenAiTranscriptionClient,
    private val gemini: GeminiTranscriptionClient,
    private val onDevice: OnDeviceSpeechTranscriber
) {

    suspend fun providers(): List<TranscriptionProvider> = listOfNotNull(cloudProvider(), onDevice)

    /** 条件を満たせばクラウドの文字起こし。満たさなければ null。 */
    suspend fun cloudProvider(): TranscriptionProvider? {
        if (appSettings.llmBackend.first() != LlmBackend.API) return null
        if (!appSettings.sendFilesToCloud.first()) return null
        return when (val provider = appSettings.apiProvider.first()) {
            ApiProvider.OPENAI -> {
                val key = apiKeyRepository.getKey(provider) ?: return null
                object : TranscriptionProvider {
                    override val name = "openai"
                    override suspend fun transcribe(chunk: PcmChunk): String =
                        openAi.transcribe(chunk.toWavBytes(), key)
                }
            }
            ApiProvider.GEMINI -> {
                val key = apiKeyRepository.getKey(provider) ?: return null
                val model = appSettings.apiModels.first()[provider] ?: provider.defaultModel
                object : TranscriptionProvider {
                    override val name = "gemini"
                    override suspend fun transcribe(chunk: PcmChunk): String =
                        gemini.transcribe(chunk.toWavBytes(), key, model)
                }
            }
            // Claude は音声を入力にできないため、端末内で文字起こしする
            ApiProvider.CLAUDE -> null
        }
    }
}
