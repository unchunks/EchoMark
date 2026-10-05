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
import com.unchunks.echomark.data.ai.LongTextConfig
import com.unchunks.echomark.data.ai.LongTextDigester
import com.unchunks.echomark.data.ai.PreparedBody
import com.unchunks.echomark.data.ai.model.LocalModelInfo
import com.unchunks.echomark.data.ai.model.ModelManager
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.model.AnalysisInput
import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.provider.LlmException
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
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

    /** 読み込み済みのエンジンと、その元になったモデル。[inferenceMutex] を持っている間だけ読み書きする。 */
    private var loaded: Pair<LocalModelInfo, LlmInference>? = null

    // LlmInference は同時に複数の生成を実行できないため直列化する。
    // エンジンの作成・破棄も同じロックの中で行い、生成中のエンジンを別のコルーチンが閉じないようにする
    // (取り込み直し・削除の直後に閉じると、ネイティブ資源の解放後に使ってしまう)
    private val inferenceMutex = Mutex()

    private val digester = LongTextDigester(LONG_TEXT)

    /** 現在のモデルのエンジン。必ず [inferenceMutex] を持った状態で呼ぶ。 */
    private fun engineLocked(): Pair<LocalModelInfo, LlmInference> {
        val model = modelManager.installedModel.value
        val file = modelManager.modelFile()
        if (model == null || file == null) {
            // モデルが削除された場合は保持しているエンジンも破棄する
            loaded?.second?.close()
            loaded = null
            throw ModelNotAvailableException()
        }
        loaded?.let { if (it.first == model) return it }
        loaded?.second?.close()
        loaded = null
        val options = LlmInferenceOptions.builder()
            .setModelPath(file.absolutePath)
            .setMaxTokens(MAX_TOKENS)
            .build()
        return (model to LlmInference.createFromOptions(context, options)).also { loaded = it }
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
            inferenceMutex.withLock {
                val (model, engine) = engineLocked()
                newSession(model, engine).use { session ->
                    session.addQueryChunk(prompt)
                    session.generateResponse()
                }
            }
        }

    /**
     * 解析用の生成。[generate] と違い、時間の上限([timeoutMs]。既定は [ANALYZE_TIMEOUT_MS])を設け、コルーチンのキャンセルでも生成を中断する
     * (ブロッキングの generateResponse はキャンセルできず、止まらない生成がロックを持ち続けて後続の処理まで止めるため)。
     * 出力の JSON オブジェクトが閉じたら、それ以降の生成(小型モデルが繰り返しに陥った続きなど)は待たずに打ち切る。
     * @throws LlmException.Timeout 時間内に生成が終わらなかったとき
     */
    private suspend fun generateAnalysis(prompt: String, timeoutMs: Long = ANALYZE_TIMEOUT_MS): String =
        withContext(dispatcherProvider.default) {
            inferenceMutex.withLock {
                val (model, engine) = engineLocked()
                newSession(model, engine).use { session ->
                    session.addQueryChunk(prompt)
                    // 出力と JSON の検出は MediaPipe のスレッドで更新し、生成が終わってから読む
                    val output = StringBuilder()
                    val jsonEnd = JsonObjectEndDetector()
                    // 生成の終了(失敗を含む)か、JSON が閉じたときに完了する
                    val stop = CompletableDeferred<Unit>()
                    val future = session.generateResponseAsync(ProgressListener<String> { partial, _ ->
                        val closed = synchronized(output) {
                            // 閉じた後の出力は捨てる(中断が効くまでに届いた分)
                            if (jsonEnd.endIndex >= 0) {
                                true
                            } else {
                                output.append(partial)
                                jsonEnd.feed(partial)
                            }
                        }
                        if (closed) stop.complete(Unit)
                    })
                    future.addListener({ stop.complete(Unit) }, Runnable::run)
                    val timedOut = try {
                        withTimeoutOrNull(timeoutMs) { stop.await() } == null
                    } finally {
                        // 時間切れ・JSON の完成・キャンセルで止める場合は生成を中断し、終わるのを待ってからセッションを閉じる
                        if (!future.isDone) runCatching { session.cancelGenerateResponseAsync() }
                        withContext(NonCancellable) { future.awaitDone() }
                    }
                    if (timedOut) throw LlmException.Timeout(timeoutMs)
                    // JSON が閉じた時点で打ち切った場合は、そこまでの出力を使う(中断による future の失敗は無視する)
                    val untilJsonEnd = synchronized(output) {
                        jsonEnd.endIndex.takeIf { it >= 0 }?.let { output.substring(0, it) }
                    }
                    untilJsonEnd ?: try {
                        future.get()
                    } catch (e: ExecutionException) {
                        throw e.cause ?: e
                    } catch (e: CancellationException) {
                        // こちらから止めていないのに生成が取り消された。コルーチンのキャンセルと区別するため別の例外にする
                        throw IllegalStateException("端末内 AI の生成が中断されました", e)
                    }
                }
            }
        }

    override suspend fun analyze(input: AnalysisInput, existingTags: List<String>): BookmarkAnalysis {
        // 上限を超える本文は、部分ごとに要約してからまとめる
        val body = digester.prepare(input.text) { part ->
            val prompt = listOf(
                AiPrompts.partInstructions(input.kind, LONG_TEXT.noteMaxChars),
                "",
                AiPrompts.partInput(input.title, part)
            ).joinToString("\n")
            AnalysisParser.parseNotes(generateAnalysis(prompt, PART_TIMEOUT_MS))
        }
        val isLong = body is PreparedBody.Digest || input.text.length > AiPrompts.LONG_TEXT_CHARS
        val response = generateAnalysis(buildAnalyzePrompt(input, body.text, isLong, existingTags))
        return AnalysisParser.parse(response, input.combinedText())
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
        inferenceMutex.withLock {
            val (model, engine) = engineLocked()
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

    private fun buildAnalyzePrompt(
        input: AnalysisInput,
        body: String,
        isLong: Boolean,
        existingTags: List<String>
    ): String = listOf(
        AiPrompts.analyzeInstructions(
            input.kind,
            existingTags,
            summaryMaxChars = AiPrompts.summaryMaxChars(input.kind, isLong),
            maxExistingTags = AiPrompts.LOCAL_MAX_EXISTING_TAGS,
            maxExistingTagChars = AiPrompts.LOCAL_MAX_EXISTING_TAG_CHARS
        ),
        "",
        AiPrompts.analyzeInput(input.title, body, MAX_INPUT_CHARS)
    ).joinToString("\n")

    // 指示・文脈・履歴・質問を、MAX_TOKENS に収まる文字数の予算で組み立てる
    private fun buildChatPrompt(
        userMessage: String,
        context: List<String>,
        history: List<ChatMessage>
    ): String = LocalChatPrompt.build(userMessage, context, history)

    companion object {
        /** 入力と出力の合計トークン数の上限。プロンプトの文字数の予算は LocalChatPrompt を参照 */
        private const val MAX_TOKENS = 4096
        private const val MAX_INPUT_CHARS = 2000

        /**
         * 解析(要約・タグ)の生成にかける時間の上限。通常は CPU でも 1 分程度で終わるが、小型モデルは同じ文の繰り返しに陥ると
         * MAX_TOKENS まで生成を続け、数分〜十数分かかることがある。WorkManager は約 10 分でワーカーを止めるため、
         * それより十分短くして(モデルの読み込み・ロックの待ちの分を残す)自分で打ち切り、失敗として扱う。
         */
        private const val ANALYZE_TIMEOUT_MS = 3 * 60 * 1000L

        /** 長い本文の部分要約1回の時間の上限。出力が短いため、まとめ([ANALYZE_TIMEOUT_MS])より短くする */
        private const val PART_TIMEOUT_MS = 90 * 1000L

        /**
         * 長い本文の分割要約。入力の上限(2,000 文字)ごとに最大 4 部分(約 8,000 文字。超える分は均等に間引く)。
         * 部分要約(最長 [PART_TIMEOUT_MS])の時間の目安と、まとめ([ANALYZE_TIMEOUT_MS])を合わせても、
         * WorkManager の実行時間の上限(約 10 分)に収まるようにする
         */
        private val LONG_TEXT = LongTextConfig(
            chunkChars = MAX_INPUT_CHARS,
            maxChunks = 4,
            noteMaxChars = 300,
            timeBudgetMillis = 4 * 60 * 1000L
        )

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
