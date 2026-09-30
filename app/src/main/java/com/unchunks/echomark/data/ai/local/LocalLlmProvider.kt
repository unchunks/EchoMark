@file:Suppress("DEPRECATION")

package com.unchunks.echomark.data.ai.local

import android.content.Context
import com.google.common.util.concurrent.ListenableFuture
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import com.google.mediapipe.tasks.genai.llminference.PromptTemplates
import com.unchunks.echomark.data.ai.AiPrompts
import com.unchunks.echomark.data.ai.model.LocalModelInfo
import com.unchunks.echomark.data.ai.model.ModelManager
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.ChatRole
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * MediaPipe LLM Inference (Gemma 等) によるローカル LLM。
 * モデルは [ModelManager] で取り込んだファイルを使い、初回利用時に遅延初期化する。
 * 取り込み直し・削除を検知したらエンジンを作り直す。
 *
 * 注意: tasks-genai 0.10.35 で LLM Inference API は非推奨(保守のみ)になった。
 * 公式の移行先は LiteRT-LM。移行するまで非推奨警告をファイル単位で抑制する。
 */
@Singleton
class LocalLlmProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
    private val dispatcherProvider: DispatcherProvider
) : LlmProvider {

    /** 読み込み済みのエンジンと、その元になったモデル。 */
    private var loaded: Pair<LocalModelInfo, LlmInference>? = null
    private val initMutex = Mutex()

    // LlmInference は同時に複数の生成を実行できないため直列化する
    private val inferenceMutex = Mutex()

    private suspend fun ensureInitialized(): Pair<LocalModelInfo, LlmInference> = initMutex.withLock {
        val model = modelManager.installedModel.value
        val file = modelManager.modelFile()
        if (model == null || file == null) {
            // モデルが削除された場合は保持しているエンジンも破棄する
            loaded?.second?.close()
            loaded = null
            throw ModelNotAvailableException()
        }
        loaded?.let { if (it.first == model) return@withLock it }
        loaded?.second?.close()
        val options = LlmInferenceOptions.builder()
            .setModelPath(file.absolutePath)
            .setMaxTokens(MAX_TOKENS)
            .build()
        (model to LlmInference.createFromOptions(context, options)).also { loaded = it }
    }

    /**
     * 1回の生成ごとにセッションを作る(会話履歴はプロンプトに含めるため、セッションに状態を持たせない)。
     * Gemma 系はチャットテンプレートを明示し、ユーザーターン/モデルターンの区切りを正しく付ける。
     */
    private fun newSession(model: LocalModelInfo, engine: LlmInference): LlmInferenceSession {
        val options = LlmInferenceSessionOptions.builder()
            .apply { if (model.isGemma) setPromptTemplates(GEMMA_TEMPLATES) }
            .build()
        return LlmInferenceSession.createFromOptions(engine, options)
    }

    private suspend fun generate(prompt: String): String =
        withContext(dispatcherProvider.default) {
            val (model, engine) = ensureInitialized()
            inferenceMutex.withLock {
                newSession(model, engine).use { session ->
                    session.addQueryChunk(prompt)
                    session.generateResponse()
                }
            }
        }

    override suspend fun analyze(text: String): BookmarkAnalysis {
        val response = generate(buildAnalyzePrompt(text))
        return AnalysisParser.parse(response, text)
    }

    override suspend fun chat(
        userMessage: String,
        context: List<String>,
        history: List<ChatMessage>
    ): String {
        return generate(buildChatPrompt(userMessage, context, history)).trim()
    }

    /** generateResponseAsync の部分結果(増分)を流す。収集側のキャンセルで生成を中断する。 */
    override fun chatStream(
        userMessage: String,
        context: List<String>,
        history: List<ChatMessage>
    ): Flow<String> = callbackFlow {
        val prompt = buildChatPrompt(userMessage, context, history)
        val (model, engine) = ensureInitialized()
        inferenceMutex.withLock {
            newSession(model, engine).use { session ->
                session.addQueryChunk(prompt)
                val finished = AtomicBoolean(false)
                val future = session.generateResponseAsync(ProgressListener<String> { partial, done ->
                    if (partial.isNotEmpty()) trySend(partial)
                    if (done) {
                        finished.set(true)
                        channel.close()
                    }
                })
                future.addListener({
                    try {
                        future.get()
                    } catch (e: ExecutionException) {
                        close(e.cause ?: e)
                    } catch (e: Exception) {
                        close(e)
                    }
                }, Runnable::run)
                try {
                    awaitClose()
                } finally {
                    // 途中で止められた場合は生成を中断し、終わるのを待ってからセッションを閉じる
                    if (!finished.get() && !future.isDone) runCatching { session.cancelGenerateResponseAsync() }
                    withContext(NonCancellable) { future.awaitDone() }
                }
            }
        }
    }.buffer(Channel.UNLIMITED).flowOn(dispatcherProvider.default)

    private suspend fun ListenableFuture<*>.awaitDone() {
        if (isDone) return
        suspendCancellableCoroutine { cont -> addListener({ cont.resume(Unit) }, Runnable::run) }
    }

    private fun buildAnalyzePrompt(text: String): String = listOf(
        AiPrompts.ANALYZE_INSTRUCTIONS,
        "",
        AiPrompts.analyzeInput(text, MAX_INPUT_CHARS)
    ).joinToString("\n")

    // context は呼び出し側で "[n] タイトル: 要約" に整形済み(番号は呼び出し側の付番をそのまま使う)
    // Gemma にはシステムロールが無いため、指示・文脈・履歴を1つのユーザーターンにまとめる
    private fun buildChatPrompt(
        userMessage: String,
        context: List<String>,
        history: List<ChatMessage>
    ): String {
        val historyLines = history.takeLast(MAX_HISTORY_ITEMS).map {
            val speaker = if (it.role == ChatRole.USER) "ユーザー" else "アシスタント"
            "$speaker: ${it.content.take(MAX_HISTORY_CHARS)}"
        }
        val lines = buildList {
            add(AiPrompts.chatInstructions(context.take(MAX_CONTEXT_ITEMS)))
            if (historyLines.isNotEmpty()) {
                add("")
                add("これまでの会話:")
                addAll(historyLines)
            }
            add("")
            add("質問: $userMessage")
        }
        return lines.joinToString("\n")
    }

    companion object {
        private const val MAX_TOKENS = 4096
        private const val MAX_INPUT_CHARS = 2000
        private const val MAX_CONTEXT_ITEMS = 5
        private const val MAX_HISTORY_ITEMS = 6
        private const val MAX_HISTORY_CHARS = 200

        /** Gemma の対話形式(https://ai.google.dev/gemma/docs/core/prompt-structure)。 */
        private val GEMMA_TEMPLATES: PromptTemplates = PromptTemplates.builder()
            .setUserPrefix("<start_of_turn>user\n")
            .setUserSuffix("<end_of_turn>\n")
            .setModelPrefix("<start_of_turn>model\n")
            .setModelSuffix("<end_of_turn>\n")
            .setSystemPrefix("")
            .setSystemSuffix("")
            .build()
    }
}
