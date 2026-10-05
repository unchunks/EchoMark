package com.unchunks.echomark.data.ai.api

import com.unchunks.echomark.data.ai.AiPrompts
import com.unchunks.echomark.data.ai.LongTextConfig
import com.unchunks.echomark.data.ai.LongTextDigester
import com.unchunks.echomark.data.ai.PreparedBody
import com.unchunks.echomark.data.ai.local.AnalysisParser
import com.unchunks.echomark.domain.model.AnalysisInput
import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.ChatRole
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.provider.LlmException
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.repository.ApiKeyRepository
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * クラウド API による [LlmProvider]。設定で選ばれた提供元([ApiProvider])の [ApiLlmClient] に委譲する。
 * 提供元・モデル・キーは呼び出しのたびに読むため、設定変更は次の呼び出しから反映される。
 */
@Singleton
class ApiLlmProvider @Inject constructor(
    claude: ClaudeApiClient,
    gemini: GeminiApiClient,
    openAi: OpenAiApiClient,
    private val appSettings: AppSettingsRepository,
    private val apiKeyRepository: ApiKeyRepository
) : LlmProvider {

    private val clients: Map<ApiProvider, ApiLlmClient> =
        listOf(claude, gemini, openAi).associateBy { it.provider }

    /** 現在の設定での呼び出し先。キー未設定なら [LlmException.ApiKeyMissing]。 */
    private suspend fun current(): Pair<ApiLlmClient, ApiCredentials> {
        val provider = appSettings.apiProvider.first()
        val model = appSettings.apiModels.first()[provider] ?: provider.defaultModel
        val apiKey = apiKeyRepository.getKey(provider) ?: throw LlmException.ApiKeyMissing(provider)
        return clients.getValue(provider) to ApiCredentials(apiKey, model)
    }

    override suspend fun analyze(input: AnalysisInput, existingTags: List<String>): BookmarkAnalysis {
        val (client, credentials) = current()
        // 上限を超える本文は、部分ごとに要約してからまとめる
        val body = digester.prepare(input.text) { part ->
            val request = ApiRequest(
                purpose = ApiPurpose.ANALYZE,
                system = AiPrompts.partInstructions(input.kind, LONG_TEXT.noteMaxChars),
                messages = listOf(ApiMessage(ChatRole.USER, AiPrompts.partInput(input.title, part)))
            )
            AnalysisParser.parseNotes(client.complete(request, credentials))
        }
        val isLong = body is PreparedBody.Digest || input.text.length > AiPrompts.LONG_TEXT_CHARS
        val request = ApiRequest(
            purpose = ApiPurpose.ANALYZE,
            system = AiPrompts.analyzeInstructions(
                input.kind,
                existingTags,
                summaryMaxChars = AiPrompts.summaryMaxChars(input.kind, isLong)
            ),
            messages = listOf(
                ApiMessage(ChatRole.USER, AiPrompts.analyzeInput(input.title, body.text, MAX_INPUT_CHARS))
            )
        )
        return AnalysisParser.parse(client.complete(request, credentials), input.combinedText())
    }

    override suspend fun chat(
        userMessage: String,
        context: List<String>,
        history: List<ChatMessage>
    ): String {
        val (client, credentials) = current()
        return client.complete(chatRequest(userMessage, context, history), credentials).trim()
    }

    override fun chatStream(
        userMessage: String,
        context: List<String>,
        history: List<ChatMessage>
    ): Flow<String> = flow {
        val (client, credentials) = current()
        emitAll(client.stream(chatRequest(userMessage, context, history), credentials))
    }

    /**
     * 短いリクエストで疎通を確認する。成功すれば何も返さず、失敗は [LlmException] で投げる。
     * @param apiKey 入力中のキー。null なら保存済みのキーを使う
     */
    suspend fun testConnection(provider: ApiProvider, model: String, apiKey: String?) {
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: apiKeyRepository.getKey(provider)
            ?: throw LlmException.ApiKeyMissing(provider)
        val request = ApiRequest(
            purpose = ApiPurpose.CONNECTION_TEST,
            system = "",
            messages = listOf(ApiMessage(ChatRole.USER, AiPrompts.CONNECTION_TEST_MESSAGE))
        )
        clients.getValue(provider).complete(request, ApiCredentials(key, model.trim().ifEmpty { provider.defaultModel }))
    }

    private fun chatRequest(
        userMessage: String,
        context: List<String>,
        history: List<ChatMessage>
    ): ApiRequest = ApiRequest(
        purpose = ApiPurpose.CHAT,
        system = AiPrompts.chatInstructions(context.take(MAX_CONTEXT_ITEMS)),
        messages = history.takeLast(MAX_HISTORY_ITEMS).map { ApiMessage(it.role, it.content.take(MAX_HISTORY_CHARS)) } +
            ApiMessage(ChatRole.USER, userMessage)
    )

    private val digester = LongTextDigester(LONG_TEXT)

    private companion object {
        // クラウドはローカルより文脈長に余裕があるため、多めに渡す
        const val MAX_INPUT_CHARS = 20_000

        /**
         * 長い本文の分割要約。1回の上限(20,000 文字)ごとに最大 6 部分(約 12 万文字。超える分は均等に間引く)。
         * 呼び出しは部分の数 + 1 回。時間の目安は WorkManager の実行時間の上限(約 10 分)に収まるようにする
         */
        val LONG_TEXT = LongTextConfig(
            chunkChars = MAX_INPUT_CHARS,
            maxChunks = 6,
            noteMaxChars = 1_200,
            timeBudgetMillis = 4 * 60 * 1000L
        )
        const val MAX_CONTEXT_ITEMS = 5
        const val MAX_HISTORY_ITEMS = 6
        const val MAX_HISTORY_CHARS = 2_000
    }
}
