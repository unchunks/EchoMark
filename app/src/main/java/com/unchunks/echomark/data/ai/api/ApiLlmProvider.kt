package com.unchunks.echomark.data.ai.api

import com.unchunks.components.llm.ApiProvider
import com.unchunks.components.llm.ChatRole
import com.unchunks.components.llm.LlmException
import com.unchunks.components.llm.LongTextConfig
import com.unchunks.components.llm.LongTextDigester
import com.unchunks.components.llm.PreparedBody
import com.unchunks.components.llm.cloud.ApiAttachment
import com.unchunks.components.llm.cloud.ApiCredentials
import com.unchunks.components.llm.cloud.ApiInputBudget
import com.unchunks.components.llm.cloud.ApiJsonSchema
import com.unchunks.components.llm.cloud.ApiLlmClient
import com.unchunks.components.llm.cloud.ApiMessage
import com.unchunks.components.llm.cloud.ApiPurpose
import com.unchunks.components.llm.cloud.ApiRequest
import com.unchunks.components.llm.cloud.ApiTokenUsage
import com.unchunks.components.llm.cloud.AttachmentKind
import com.unchunks.components.llm.cloud.AttachmentPolicy
import com.unchunks.components.llm.cloud.ClaudeApiClient
import com.unchunks.components.llm.cloud.GeminiApiClient
import com.unchunks.components.llm.cloud.OpenAiApiClient
import com.unchunks.components.llm.cloud.completeJson
import com.unchunks.echomark.data.ai.AiPrompts
import com.unchunks.echomark.data.ai.AnalysisSchema
import com.unchunks.echomark.data.ai.StructuredOutput
import com.unchunks.echomark.data.ai.local.AnalysisParser
import com.unchunks.echomark.domain.model.AnalysisInput
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.provider.NothingToAnalyzeException
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.ApiKeyRepository
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.aiTaskSetting
import com.unchunks.echomark.domain.usage.ApiUsageRecord
import com.unchunks.echomark.domain.usage.ApiUsageRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 用途([AiTask])ごとの設定(提供元・モデル)で動く、クラウド API の [LlmProvider] を返す。 */
fun interface ApiLlmProviderFactory {
    fun forTask(task: AiTask): LlmProvider
}

/**
 * クラウド API による [LlmProvider]。用途ごとの設定で選ばれた提供元([ApiProvider])の [ApiLlmClient] に委譲する。
 * 提供元・モデル・キーは呼び出しのたびに読むため、設定変更は次の呼び出しから反映される。
 */
@Singleton
class ApiLlmProvider @Inject constructor(
    claude: ClaudeApiClient,
    gemini: GeminiApiClient,
    openAi: OpenAiApiClient,
    private val appSettings: AppSettingsRepository,
    private val apiKeyRepository: ApiKeyRepository,
    private val attachmentLoader: AttachmentLoader,
    private val usageRepository: ApiUsageRepository
) : ApiLlmProviderFactory {

    override fun forTask(task: AiTask): LlmProvider = TaskProvider(task)

    /** [task] の設定で呼び出す [LlmProvider]。 */
    private inner class TaskProvider(private val task: AiTask) : LlmProvider {
        override suspend fun analyze(
            input: AnalysisInput,
            existingTags: List<String>,
            scope: AnalysisScope
        ): BookmarkAnalysis = analyze(task, input, existingTags, scope)

        override suspend fun chat(userMessage: String, context: List<String>, history: List<ChatMessage>): String =
            chat(task, userMessage, context, history)

        override fun chatStream(userMessage: String, context: List<String>, history: List<ChatMessage>): Flow<String> =
            chatStream(task, userMessage, context, history)

        override suspend fun complete(prompt: String): String {
            val (client, credentials) = current(task)
            val request = ApiRequest(
                purpose = ApiPurpose.ANALYZE,
                system = prompt,
                messages = listOf(ApiMessage(ChatRole.USER, "上の指示に従い、JSON だけを出力してください。"))
            )
            return recordingUsage(task, client, credentials) { onUsage -> client.completeJson(request, credentials, onUsage) }
        }
    }

    private val clients: Map<ApiProvider, ApiLlmClient> =
        listOf(claude, gemini, openAi).associateBy { it.provider }

    /** [task] の現在の設定での呼び出し先。キー未設定なら [LlmException.ApiKeyMissing]。 */
    private suspend fun current(task: AiTask): Pair<ApiLlmClient, ApiCredentials> {
        val setting = appSettings.aiTaskSetting(task).first()
        val provider = setting.apiProvider
        val model = setting.apiModel
        val apiKey = apiKeyRepository.getKey(provider) ?: throw LlmException.ApiKeyMissing(provider)
        return clients.getValue(provider) to ApiCredentials(apiKey, model)
    }

    private suspend fun analyze(
        task: AiTask,
        input: AnalysisInput,
        existingTags: List<String>,
        scope: AnalysisScope
    ): BookmarkAnalysis {
        val (client, credentials) = current(task)
        val attachment = prepareAttachment(input, client.provider)
        // ファイル名だけの要約は役に立たないため作らない(設定を変えた後の再処理を待つ)
        if (attachment == null && input.attachment != null && input.text.isBlank()) throw NothingToAnalyzeException()
        // PDF・音声・動画をそのまま渡すときは、ファイルが主役。取り出したテキストは手がかりとして先頭だけ渡す
        val fileIsPrimary = attachment != null && attachment.kind != AttachmentKind.IMAGE
        val config = ApiInputBudget.longTextConfig(client.provider, credentials.model)
        return try {
            analyzeWith(task, config, client, credentials, input, existingTags, scope, attachment, fileIsPrimary)
        } catch (e: LlmException.BadRequest) {
            // 1回で渡す長さの見積もりが外れた(文脈長を超えた)ときは、従来の小さい単位で分けて要約し直す
            val fallback = ApiInputBudget.fallbackConfig()
            if (fileIsPrimary || input.text.length <= fallback.singleCallChars || config.singleCallChars <= fallback.singleCallChars) {
                throw e
            }
            Timber.w(e, "本文を1回で渡せなかったため、分けて要約し直す: %d 文字", input.text.length)
            analyzeWith(task, fallback, client, credentials, input, existingTags, scope, attachment, fileIsPrimary)
        }
    }

    /**
     * [config] の上限を超える本文は、部分ごとに要約してからまとめる。上限以下なら1回の呼び出しで要約する。
     * 応答(JSON)はストリーミングで受け、JSON が閉じた時点で打ち切る([completeJson])。
     */
    private suspend fun analyzeWith(
        task: AiTask,
        config: LongTextConfig,
        client: ApiLlmClient,
        credentials: ApiCredentials,
        input: AnalysisInput,
        existingTags: List<String>,
        scope: AnalysisScope,
        attachment: ApiAttachment?,
        fileIsPrimary: Boolean
    ): BookmarkAnalysis {
        val body = if (fileIsPrimary) {
            PreparedBody.Whole(input.text)
        } else {
            LongTextDigester(config).prepare(input.text) { part ->
                val request = ApiRequest(
                    purpose = ApiPurpose.ANALYZE,
                    system = AiPrompts.partInstructions(input.kind, config.noteMaxChars, input.profile),
                    messages = listOf(ApiMessage(ChatRole.USER, AiPrompts.partInput(input.title, part))),
                    jsonSchema = ApiJsonSchema(AnalysisSchema.NOTES_NAME, AnalysisSchema.notes())
                )
                // メモは壊れていても緩く読めるため、作り直させない(部分ごとに呼び出しが増えるため)
                AnalysisParser.parseNotes(completeJson(task, client, request, credentials))
            }
        }
        val isLong = fileIsPrimary || body is PreparedBody.Digest || input.text.length > AiPrompts.LONG_TEXT_CHARS
        val request = ApiRequest(
            purpose = ApiPurpose.ANALYZE,
            system = AiPrompts.analyzeInstructions(
                input.kind,
                existingTags,
                summaryMaxChars = AiPrompts.summaryMaxChars(input.kind, isLong, input.style),
                scope = scope,
                profile = input.profile,
                style = input.style,
                feedback = input.feedback,
                categories = input.categories
            ),
            messages = listOf(
                ApiMessage(
                    role = ChatRole.USER,
                    text = AiPrompts.analyzeInput(
                        input.title,
                        body.text,
                        maxChars = if (fileIsPrimary) MAX_TEXT_WITH_FILE_CHARS else config.singleCallChars,
                        attachmentLabel = attachment?.let { AttachmentPolicy.label(it.kind) }
                    ),
                    attachments = listOfNotNull(attachment)
                )
            ),
            jsonSchema = ApiJsonSchema(AnalysisSchema.ANALYSIS_NAME, AnalysisSchema.analysis(scope))
        )
        val response = StructuredOutput.generateWithRepair(
            maxRepairs = MAX_REPAIRS,
            label = "${client.provider} ${credentials.model}",
            validate = { StructuredOutput.problemOf(it, scope) }
        ) { problem, previous ->
            // 作り直しは、前の出力と直す指示を会話の続きとして渡す(元の指示・本文・添付はそのまま)
            val repair = if (problem == null || previous == null) {
                request
            } else {
                request.copy(
                    messages = request.messages +
                        ApiMessage(ChatRole.ASSISTANT, previous) +
                        ApiMessage(ChatRole.USER, AiPrompts.repairInstructions(problem, scope))
                )
            }
            completeJson(task, client, repair, credentials)
        }
        return AnalysisParser.parse(response, input.combinedText())
    }

    /**
     * スキーマで縛った JSON を返させる([completeJson])。スキーマを付けたリクエストが拒否されたら(HTTP 400 など。
     * モデルが構造化出力に対応していない・スキーマの書き方を受け付けない)、スキーマを外してやり直し、成功したらその提供元とモデルでは
     * 以後スキーマを付けない。外しても拒否されるなら(本文が長すぎるなど、スキーマのせいではない)その失敗をそのまま投げる。
     */
    private suspend fun completeJson(
        task: AiTask,
        client: ApiLlmClient,
        request: ApiRequest,
        credentials: ApiCredentials
    ): String {
        val key = "${client.provider}:${credentials.model}"
        // 呼び出しごとに使用量を記録する(拒否されたやり直しの前の呼び出しは、トークン数が無ければ記録しない)
        suspend fun call(request: ApiRequest) =
            recordingUsage(task, client, credentials) { onUsage -> client.completeJson(request, credentials, onUsage) }
        if (request.jsonSchema == null || key in schemaRejected) {
            return call(request.copy(jsonSchema = null))
        }
        return try {
            call(request)
        } catch (e: LlmException.BadRequest) {
            val result = call(request.copy(jsonSchema = null))
            Timber.w(e, "構造化出力を受け付けないため、以後スキーマを付けない: %s", key)
            schemaRejected += key
            result
        }
    }

    /** 構造化出力のスキーマを拒否した「提供元:モデル」(アプリを起動し直すまで覚える) */
    private val schemaRejected: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * 元のファイルを提供元にそのまま渡せるなら読み込む。設定でオフ・提供元が受け付けない種類・大きすぎる・読めないなら null
     * (端末内で取り出したテキストだけで要約する)。
     */
    private suspend fun prepareAttachment(input: AnalysisInput, provider: ApiProvider): ApiAttachment? {
        val attachment = input.attachment ?: return null
        if (!appSettings.sendFilesToCloud.first()) return null
        val kind = AttachmentPolicy.plan(provider, attachment.mimeType, attachment.sizeBytes) ?: return null
        return attachmentLoader.load(attachment, kind)
    }

    private suspend fun chat(
        task: AiTask,
        userMessage: String,
        context: List<String>,
        history: List<ChatMessage>
    ): String {
        val (client, credentials) = current(task)
        val request = chatRequest(userMessage, context, history)
        return recordingUsage(task, client, credentials) { onUsage -> client.complete(request, credentials, onUsage) }.trim()
    }

    private fun chatStream(
        task: AiTask,
        userMessage: String,
        context: List<String>,
        history: List<ChatMessage>
    ): Flow<String> = flow {
        val (client, credentials) = current(task)
        var latest: ApiTokenUsage? = null
        // 途中で止められても(収集のキャンセル・失敗)、そこまでに分かったトークン数を記録する
        emitAll(
            client.stream(chatRequest(userMessage, context, history), credentials) { latest = it }
                .onCompletion { recordUsage(task, client, credentials, latest) }
        )
    }

    /**
     * [call] に使用量の受け取りを渡して呼び、終わったら(失敗・打ち切りでも)分かったトークン数を記録する。
     * 記録できなくても AI の呼び出しの結果は変えない。
     */
    private suspend fun <T> recordingUsage(
        task: AiTask,
        client: ApiLlmClient,
        credentials: ApiCredentials,
        call: suspend ((ApiTokenUsage) -> Unit) -> T
    ): T {
        var latest: ApiTokenUsage? = null
        try {
            return call { latest = it }
        } finally {
            recordUsage(task, client, credentials, latest)
        }
    }

    private suspend fun recordUsage(task: AiTask, client: ApiLlmClient, credentials: ApiCredentials, usage: ApiTokenUsage?) {
        if (usage == null) return
        // 呼び出しのキャンセルで記録を落とさない
        withContext(NonCancellable) {
            try {
                usageRepository.record(
                    ApiUsageRecord(
                        createdAt = System.currentTimeMillis(),
                        provider = client.provider,
                        model = credentials.model,
                        task = task,
                        inputTokens = usage.inputTokens,
                        outputTokens = usage.outputTokens
                    )
                )
            } catch (e: Exception) {
                Timber.w(e, "API の使用量を記録できない")
            }
        }
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

    private companion object {
        /**
         * PDF・音声・動画のファイルそのものを渡すときに、一緒に渡すテキストの上限(ファイルと重複するため短く)。
         * ファイルを渡さないときの本文の上限は、モデルごとの [ApiInputBudget]。
         */
        const val MAX_TEXT_WITH_FILE_CHARS = 4_000

        /** 要約の出力の形が正しくなかったときに作り直させる回数の上限(1回の解析で呼び出しは最大 1 + この回数) */
        const val MAX_REPAIRS = 2

        const val MAX_CONTEXT_ITEMS = 5
        const val MAX_HISTORY_ITEMS = 6
        const val MAX_HISTORY_CHARS = 2_000
    }
}
