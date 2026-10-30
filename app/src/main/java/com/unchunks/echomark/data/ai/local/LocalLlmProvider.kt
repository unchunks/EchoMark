package com.unchunks.echomark.data.ai.local

import android.content.Context
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.components.llm.ChatOutputGuard
import com.unchunks.components.llm.JsonObjectEndDetector
import com.unchunks.components.llm.LlmException
import com.unchunks.components.llm.LongTextConfig
import com.unchunks.components.llm.LongTextDigester
import com.unchunks.components.llm.PreparedBody
import com.unchunks.components.llm.ondevice.BackendSelector
import com.unchunks.components.llm.ondevice.EngineBackend
import com.unchunks.components.llm.ondevice.FileBackendStore
import com.unchunks.components.llm.ondevice.GenerationKind
import com.unchunks.components.llm.ondevice.LiteRtLmTextEngine
import com.unchunks.components.llm.ondevice.LocalTextEngine
import com.unchunks.components.llm.ondevice.isInputTooLong
import com.unchunks.components.llm.ondevice.shouldReleaseEngineOnTrim
import com.unchunks.echomark.data.ai.AiPerfLog
import com.unchunks.echomark.data.ai.AiPrompts
import com.unchunks.echomark.data.ai.AnalysisSchema
import com.unchunks.echomark.data.ai.LocalLlmProviders
import com.unchunks.echomark.data.ai.StructuredOutput
import com.unchunks.echomark.data.ai.model.LocalModelInfo
import com.unchunks.echomark.data.ai.model.ModelManager
import com.unchunks.echomark.domain.model.AnalysisInput
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import com.unchunks.echomark.domain.provider.NothingToAnalyzeException
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LiteRT-LM(Gemma・Qwen 等)によるローカル LLM。.litertlm 形式のモデルと、旧形式の .task のモデルの両方を LiteRT-LM で動かす
 * (MediaPipe の LLM Inference は非推奨になったため使わない)。
 * モデルは [ModelManager] で取り込んだファイルを使い、初回利用時(または [prewarm])に遅延初期化する。
 * 取り込み直し・削除を検知したらエンジンを作り直す。GPU でも CPU でもエンジンを作れなければ [ModelManager.reportLoadFailure]
 * で設定画面に知らせ、取り込み直しを促す(旧形式の .task には LiteRT-LM で読めないものがあるため)。
 *
 * 速度とメモリの方針:
 * - 演算装置: 既定は CPU。AI 設定で GPU を使う([AppSettingsRepository.localLlmUseGpu])ときだけ GPU を先に試し、
 *   作れなければ CPU にする([BackendSelector])。GPU は端末・モデルによってはネイティブのコードで落ちる(例外にならず
 *   アプリごと終了する)ため既定にはしない。動いた装置はモデルごとに覚え(メモリ + `filesDir/models/local_llm_backend.json`)、
 *   CPU に落ちたモデルは次から GPU を試さない。GPU で作れても最初の推論が失敗した・その途中でアプリが落ちたら、以後は CPU を使う。
 *   どちらを使ったかは Timber(`AiPerf` タグ含む)に出す
 * - 先読み: [prewarm] で、AI 処理の前段(本文取得など)と重ねてエンジンを読み込んでおく(数秒〜十数秒の短縮)
 * - 解放: エンジンはジョブの間は保持するが、最後に使ってから [IDLE_RELEASE_MS] 経つか、メモリが逼迫したとき
 *   ([trimMemory]。使用中は解放しない)に閉じる。モデルは数百MB〜数GBあり、持ち続けるとバックグラウンドの
 *   プロセスが OOM で終了されやすくなるため。次に使うときに読み込み直す
 * - 用途ごとのモデル: [forModel] で、用途ごとに選んだモデルで動く [LlmProvider] を返す。同時に読み込むモデルは1つだけで、
 *   別のモデルを使う呼び出しが来たら、読み込んでいるモデルを解放してから読み込む(2つ持つとメモリが足りなくなるため)。
 *   この [LlmProvider] としての呼び出し(analyze など)は既定のモデルを使う
 */
@Singleton
class LocalLlmProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
    private val appSettings: AppSettingsRepository,
    private val dispatcherProvider: DispatcherProvider
) : LlmProvider, LocalLlmProviders {

    /** [modelId] のモデル(null・見つからなければ既定のモデル)で動く端末内 AI。 */
    override fun forModel(modelId: String?): LlmProvider = object : LlmProvider {
        override suspend fun analyze(input: AnalysisInput, existingTags: List<String>, scope: AnalysisScope) =
            this@LocalLlmProvider.analyze(modelId, input, existingTags, scope)

        override suspend fun chat(userMessage: String, context: List<String>, history: List<ChatMessage>) =
            this@LocalLlmProvider.chatStream(modelId, userMessage, context, history)
                .fold("") { acc, chunk -> acc + chunk }.trim()

        override fun chatStream(userMessage: String, context: List<String>, history: List<ChatMessage>) =
            this@LocalLlmProvider.chatStream(modelId, userMessage, context, history)

        override suspend fun complete(prompt: String) = this@LocalLlmProvider.complete(modelId, prompt)

        override fun prewarm() = this@LocalLlmProvider.prewarm(modelId)
    }

    /** 読み込み済みのエンジン。[verified] は、このエンジンで推論に成功したことがあるか。 */
    private class LoadedEngine(
        val model: LocalModelInfo,
        val engine: LocalTextEngine,
        val backend: EngineBackend,
        val modelKey: String
    ) {
        var verified = false
    }

    /**
     * 読み込み済みのエンジン。書き込みは [inferenceMutex] を持っている間だけ。
     * [prewarm] が無駄な待ちを作らないよう、読み取りだけはロックなしでも行う(@Volatile)。
     */
    @Volatile
    private var loaded: LoadedEngine? = null

    // LlmInference は同時に複数の生成を実行できないため直列化する。
    // エンジンの作成・破棄も同じロックの中で行い、生成中のエンジンを別のコルーチンが閉じないようにする
    // (取り込み直し・削除の直後に閉じると、ネイティブ資源の解放後に使ってしまう)
    private val inferenceMutex = Mutex()

    /** 先読み・アイドル時の解放など、呼び出し元を待たせないバックグラウンドの処理を動かす。 */
    // 想定外の失敗(Error を含む)でアプリを落とさない。先読み・解放は失敗しても推論の側でやり直せる
    private val backgroundScope = CoroutineScope(
        SupervisorJob() + dispatcherProvider.default +
            CoroutineExceptionHandler { _, e -> Timber.e(e, "端末内モデルのバックグラウンド処理に失敗") }
    )

    private val backendSelector by lazy {
        // models/ はバックアップの対象外(GPU の性能が違う別の端末に復元されない)
        BackendSelector(FileBackendStore(File(File(context.filesDir, "models"), BACKEND_FILE_NAME)))
    }

    /** エンジンを作る処理(テストで差し替える) */
    internal var engineFactory: (File, LocalModelInfo, EngineBackend) -> LocalTextEngine = ::createEngine

    private val digester = LongTextDigester(LONG_TEXT)

    private val lifecycleLock = Any()
    private var prewarmJob: Job? = null
    private var idleReleaseJob: Job? = null

    @Volatile
    private var lastUsedNanos = 0L

    /**
     * 画面で結果を待っている生成(チャット・資料の選択)の数。0 でない間、背景の解析(要約・タグ)は新しい生成を始めず、
     * 生成中なら [preemptBackground] で中断して、画面での生成が終わってからやり直す
     * (背景の解析は1回に数十秒〜数分かかり、終わるまでチャットの回答が始まらないと、止まっているように見えるため)。
     */
    private val interactiveCount = MutableStateFlow(0)

    /** 生成中の背景の解析を中断する処理。生成中でなければ null。 */
    @Volatile
    private var preemptBackground: (() -> Unit)? = null

    init {
        latest = WeakReference(this)
    }

    /**
     * [modelId] のモデル(null・見つからなければ既定のモデル)のエンジン。別のモデルを読み込んでいれば、解放してから読み込む。
     * 必ず [inferenceMutex] を持った状態で呼ぶ。
     */
    private suspend fun engineLocked(modelId: String?): LoadedEngine {
        val model = modelManager.installed.value.resolve(modelId)
        val file = model?.let(modelManager::modelFile)
        if (model == null || file == null) {
            // モデルが削除された場合は保持しているエンジンも破棄する
            releaseLocked("モデルが無い")
            throw ModelNotAvailableException()
        }
        val allowGpu = appSettings.localLlmUseGpu.first()
        // GPU の設定をオフにしたら、GPU で読み込んだエンジンは CPU で作り直す
        loaded?.let { if (it.model == model && (allowGpu || it.backend == EngineBackend.CPU)) return it }
        releaseLocked("使うモデルか演算装置の設定が変わった")
        val modelKey = "${model.fileName}:${model.sizeBytes}:${model.importedAt}"
        val start = System.nanoTime()
        val created = try {
            backendSelector.create(modelKey, allowGpu) { backend -> engineFactory(file, model, backend) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkageError) {
            // ライブラリの読み込みの失敗はモデルのせいではない(withEngine で端末内 AI が使えない例外にする)
            throw e
        } catch (e: Throwable) {
            Timber.w(e, "端末内モデルを読み込めない: %s", model.displayName)
            modelManager.reportLoadFailure(model, e)
            throw e
        }
        modelManager.reportLoaded(model)
        val millis = (System.nanoTime() - start) / NANOS_PER_MILLI
        Timber.i("端末内モデルを読み込み: backend=%s %d ms (%s)", created.backend, millis, model.displayName)
        AiPerfLog.record(
            "llm.engineLoad", millis, "backend=${created.backend} model=${model.displayName}",
            AiPerfLog.currentBookmarkId()
        )
        return LoadedEngine(model, created.engine, created.backend, modelKey).also { loaded = it }
    }

    /**
     * LiteRT-LM のエンジンを作る(.litertlm も旧形式の .task も)。
     * 旧形式の .task の Gemma は、MediaPipe で動かしていたときと同じく会話の書式(<start_of_turn> 等)を明示する
     * (古い .task には書式が入っておらず、LiteRT-LM が会話を作れないため)。
     * 入力と出力の合計の上限([MAX_TOKENS])は、チャットの文脈の予算(LocalChatPrompt)にも使う。
     * 解析で余計な出力が続くのは、JSON が閉じたら打ち切る・出力の文字数と時間の上限で防ぐ。
     */
    private fun createEngine(file: File, model: LocalModelInfo, backend: EngineBackend): LocalTextEngine =
        LiteRtLmTextEngine.create(
            file.absolutePath,
            backend,
            MAX_TOKENS,
            context.cacheDir.path,
            chatTemplate = chatTemplateFor(model)
        )

    /** エンジンを閉じて手放す。必ず [inferenceMutex] を持った状態で呼ぶ。 */
    private fun releaseLocked(reason: String) {
        val current = loaded ?: return
        loaded = null
        try {
            current.engine.close()
        } catch (e: Exception) {
            Timber.w(e, "端末内モデルを閉じるのに失敗")
        }
        Timber.i("端末内モデルを解放: %s", reason)
    }

    /**
     * 使い終わった印を付け、[IDLE_RELEASE_MS] 後に(その間に使われていなければ)エンジンを解放する予約をし直す。
     * 必ず [inferenceMutex] を持った状態で呼ぶ。
     */
    private fun markUsedLocked() {
        lastUsedNanos = System.nanoTime()
        synchronized(lifecycleLock) {
            idleReleaseJob?.cancel()
            idleReleaseJob = backgroundScope.launch {
                delay(IDLE_RELEASE_MS)
                inferenceMutex.withLock {
                    // 待っている間に使われていれば、その終わりに予約し直されている
                    if (System.nanoTime() - lastUsedNanos >= IDLE_RELEASE_MS * NANOS_PER_MILLI) {
                        releaseLocked("${IDLE_RELEASE_MS / 1000} 秒使われなかった")
                    }
                }
            }
        }
    }

    /**
     * [block] を、エンジンを読み込んで(読み込み済みならそのまま)排他的に実行する。
     * GPU で作れたエンジンで最初の推論が失敗したら、以後は CPU を使うよう覚えてエンジンを閉じる(例外はそのまま投げる)。
     */
    private suspend fun <T> withEngine(modelId: String?, block: suspend (LoadedEngine) -> T): T =
        inferenceMutex.withLock {
            try {
                val current = engineLocked(modelId)
                try {
                    block(current).also {
                        if (!current.verified && current.backend == EngineBackend.GPU) backendSelector.confirmGpu()
                        current.verified = true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LlmException.Timeout) {
                    // 時間切れは装置の不具合とは限らない(遅い端末・繰り返し)ため、装置は変えない
                    throw e
                } catch (e: Throwable) {
                    // GPU のライブラリの読み込み失敗(UnsatisfiedLinkError)などの Error も CPU に切り替える
                    if (current.backend == EngineBackend.GPU && !current.verified) {
                        Timber.w(e, "GPU での最初の推論に失敗したため、以後は CPU を使う")
                        backendSelector.demoteToCpu(current.modelKey)
                        releaseLocked("GPU での推論の失敗")
                    } else if (!current.verified && e !is LinkageError && !isInputTooLong(e)) {
                        // CPU で一度も推論できていない(会話の書式が無い .task など、モデルが LiteRT-LM で動かない)
                        modelManager.reportLoadFailure(current.model, e)
                    }
                    throw e
                }
            } catch (e: LinkageError) {
                // LiteRT-LM のクラスの静的初期化・ネイティブライブラリの読み込みに失敗した(ExceptionInInitializerError、
                // 2回目以降は NoClassDefFoundError / UnsatisfiedLinkError)。Error のまま投げると、ワーカーは状態を
                // 「処理中」のまま残して失敗し、画面からの呼び出しではアプリごと落ちるため、端末内 AI が使えない例外にする
                Timber.e(e, "端末内 AI のライブラリを初期化できない")
                throw LlmException.EngineUnavailable(e)
            } finally {
                markUsedLocked()
            }
        }

    /**
     * エンジンの読み込みを先に始める(完了は待たない)。AI 処理の前段の本文取得・取り出しと重ね、
     * 推論を始めるときに読み込み済みにする。読み込み中は [inferenceMutex] を持つため、先に推論が呼ばれても
     * 読み込みが終わるのを待つだけで、二重には読み込まない。モデルが無い・読み込みに失敗しても何も起きない(推論の側で失敗する)。
     */
    override fun prewarm() = prewarm(null)

    /** [prewarm] の、[modelId] のモデルを読み込む版。 */
    private fun prewarm(modelId: String?) {
        // 読み込み済みなら何もしない(推論中のロック待ちを増やさない)
        val current = loaded
        if (current != null && current.model == modelManager.installed.value.resolve(modelId)) return
        synchronized(lifecycleLock) {
            if (prewarmJob?.isActive == true) return
            prewarmJob = backgroundScope.launch {
                try {
                    inferenceMutex.withLock {
                        try {
                            engineLocked(modelId)
                        } finally {
                            markUsedLocked()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ModelNotAvailableException) {
                    Timber.d("端末内モデルが無いため先読みしない")
                } catch (e: Throwable) {
                    // Error(ライブラリの読み込み失敗・メモリ不足など)でもアプリを落とさない。推論の側で改めて失敗として扱う
                    Timber.w(e, "端末内モデルの先読みに失敗")
                }
            }
        }
    }

    /**
     * メモリの逼迫などで [shouldReleaseEngineOnTrim] なら、使っていないエンジンを手放す。
     * 推論中(ロック中)は止めずに何もしない。そのときは推論の後のアイドル解放に任せる。
     */
    private fun releaseOnTrim(level: Int) {
        if (!shouldReleaseEngineOnTrim(level)) return
        backgroundScope.launch {
            if (!inferenceMutex.tryLock()) {
                Timber.d("端末内モデルを使用中のため、メモリ逼迫(level=%d)でも解放しない", level)
                return@launch
            }
            try {
                releaseLocked("メモリ逼迫 level=$level")
            } finally {
                inferenceMutex.unlock()
            }
        }
    }

    /**
     * 解析用の生成。時間の上限([timeoutMillis]。既定は [ANALYZE_TIMEOUT_MS])と出力の文字数の上限
     * ([maxOutputChars])を設け、コルーチンのキャンセルでも生成を中断する
     * (ブロッキングの generateResponse はキャンセルできず、止まらない生成がロックを持ち続けて後続の処理まで止めるため)。
     * 出力の JSON オブジェクトが閉じたら、それ以降の生成(小型モデルが繰り返しに陥った続きなど)は待たずに打ち切る。
     * JSON が閉じないまま上限を超える出力は、繰り返しに陥ったとみなして時間切れと同じ扱いにする(上限の時間まで待たない)。
     * 所要時間・最初の出力までの時間・出力の速さは `AiPerf` のログに出す。
     * @param label ログ用の名前(部分要約 `part 2/4`、最終 `final`)
     * @param timeoutMillis 使っている演算装置ごとの時間の上限(装置はエンジンの読み込み後に決まる)
     * @param responseSchema 出力を従わせる JSON スキーマ(LiteRT-LM の制約付きの生成)。null なら縛らない
     * @throws LlmException.Timeout 時間内に生成が終わらなかった、または出力が上限を超えたとき
     */
    private suspend fun generateAnalysis(
        modelId: String?,
        prompt: String,
        label: String,
        maxOutputChars: Int = ANALYZE_MAX_OUTPUT_CHARS,
        timeoutMillis: (EngineBackend) -> Long = { ANALYZE_TIMEOUT_MS },
        responseSchema: String? = null
    ): String {
        // 画面で待っている生成(チャットなど)があれば、それが終わるまで始めない。生成中に割り込まれたら、終わるのを待ってやり直す
        while (true) {
            interactiveCount.first { it == 0 }
            generateAnalysisOnce(modelId, prompt, label, maxOutputChars, timeoutMillis, preemptible = true, responseSchema)
                ?.let { return it }
            Timber.i("画面での生成を先にするため、解析の生成を中断した。後でやり直す: %s", label)
        }
    }

    /**
     * [generateAnalysis] の1回分。[preemptible] なら、画面での生成([asInteractive])が始まったときに中断して null を返す
     * (始める前に画面での生成が待っていれば、何もせずに null を返す)。
     * [responseSchema] を付けた生成が失敗したら(モデルのトークナイザーなどで制約付きの生成が動かない)、スキーマを外してやり直し、
     * そのモデルでは以後スキーマを付けない。時間切れ・キャンセル・入力が長すぎる失敗は、スキーマのせいではないためそのまま投げる。
     */
    private suspend fun generateAnalysisOnce(
        modelId: String?,
        prompt: String,
        label: String,
        maxOutputChars: Int,
        timeoutMillis: (EngineBackend) -> Long,
        preemptible: Boolean,
        responseSchema: String? = null
    ): String? {
        val bookmarkId = AiPerfLog.currentBookmarkId()
        return withContext(dispatcherProvider.default) {
            withEngine(modelId) { loaded ->
                if (preemptible && interactiveCount.value > 0) return@withEngine null
                val schema = responseSchema?.takeIf { loaded.modelKey !in schemaUnsupportedModels }
                try {
                    runAnalysisGeneration(loaded, prompt, label, maxOutputChars, timeoutMillis, preemptible, bookmarkId, schema)
                } catch (e: Exception) {
                    if (schema == null || e is CancellationException || e is LlmException.Timeout || isInputTooLong(e)) throw e
                    Timber.w(e, "スキーマ付きの生成に失敗したため、このモデルでは以後スキーマを付けない: %s", loaded.model.displayName)
                    schemaUnsupportedModels += loaded.modelKey
                    if (preemptible && interactiveCount.value > 0) return@withEngine null
                    runAnalysisGeneration(loaded, prompt, label, maxOutputChars, timeoutMillis, preemptible, bookmarkId, null)
                }
            }
        }
    }

    /**
     * [generateAnalysisOnce] の、エンジンを持った状態での1回の生成。必ず [inferenceMutex] を持った状態で呼ぶ。
     * 画面での生成に割り込まれたら null。
     */
    private suspend fun runAnalysisGeneration(
        loaded: LoadedEngine,
        prompt: String,
        label: String,
        maxOutputChars: Int,
        timeoutMillis: (EngineBackend) -> Long,
        preemptible: Boolean,
        bookmarkId: Long?,
        schema: String?
    ): String? {
        val timeoutMs = timeoutMillis(loaded.backend)
        // 画面での生成に割り込まれたか(中断の後は時間切れ・出力の上限の扱いをせず、やり直させる)
        val preempted = AtomicBoolean(false)
        // 出力と JSON の検出はエンジンのスレッドで更新し、生成が終わってから読む
        val output = StringBuilder()
        val jsonEnd = JsonObjectEndDetector()
        var runaway = false
        var firstOutputNanos = 0L
        // 生成の終わり(失敗・中断を含む)。失敗ならその例外
        val finished = CompletableDeferred<Throwable?>()
        // 生成の終わりか、JSON が閉じた・出力が上限を超えたときに完了する
        val stop = CompletableDeferred<Unit>()
        val startNanos = System.nanoTime()
        val onPartial: (String) -> Unit = { partial ->
            val done = synchronized(output) {
                if (firstOutputNanos == 0L) firstOutputNanos = System.nanoTime()
                // 閉じた後・上限を超えた後の出力は捨てる(中断が効くまでに届いた分)
                if (jsonEnd.endIndex >= 0 || runaway) {
                    true
                } else {
                    output.append(partial)
                    val closed = jsonEnd.feed(partial)
                    if (!closed && output.length > maxOutputChars) runaway = true
                    closed || runaway
                }
            }
            if (done) stop.complete(Unit)
        }
        val onDone: (Throwable?) -> Unit = { error ->
            finished.complete(error)
            stop.complete(Unit)
        }
        val generation = if (schema != null) {
            loaded.engine.start(prompt, GenerationKind.JSON, schema, onPartial, onDone)
        } else {
            loaded.engine.start(prompt, GenerationKind.JSON, onPartial, onDone)
        }
        if (preemptible) {
            preemptBackground = {
                preempted.set(true)
                generation.cancel()
            }
            // 中断の処理を置く前に画面での生成が始まっていたら、ここで中断する
            if (interactiveCount.value > 0) preemptBackground?.invoke()
        }
        val timedOut = try {
            withTimeoutOrNull(timeoutMs) { stop.await() } == null
        } finally {
            if (preemptible) preemptBackground = null
            // 時間切れ・JSON の完成・キャンセルで止める場合は生成を中断し、終わるのを待つ
            if (!finished.isCompleted) generation.cancel()
            withContext(NonCancellable) { awaitFinished(finished) }
            generation.close()
        }
        val endNanos = System.nanoTime()
        if (preempted.get()) return null
        // 生成を止めた後は、エンジンのスレッドからの更新は無いため、まとめて読む
        val stats = synchronized(output) {
            GenerationStats(
                outChars = jsonEnd.endIndex.takeIf { it >= 0 } ?: output.length,
                runaway = runaway,
                firstOutputNanos = firstOutputNanos
            )
        }
        logGeneration(
            if (schema != null) "$label schema" else label,
            loaded.backend, prompt.length, stats, startNanos, endNanos, bookmarkId, timedOut
        )
        if (timedOut) throw LlmException.Timeout(timeoutMs)
        if (stats.runaway) throw LlmException.Timeout((endNanos - startNanos) / NANOS_PER_MILLI)
        // JSON が閉じた時点で打ち切った場合は、そこまでの出力を使う(中断による失敗は無視する)
        return synchronized(output) { jsonEnd.endIndex.takeIf { it >= 0 }?.let { output.substring(0, it) } }
            ?: when (val error = if (finished.isCompleted) finished.await() else null) {
                null -> synchronized(output) { output.toString() }
                // こちらから止めていないのに生成が取り消された。コルーチンのキャンセルと区別するため別の例外にする
                is java.util.concurrent.CancellationException -> throw IllegalStateException("端末内 AI の生成が中断されました", error)
                else -> throw error
            }
    }

    /** 制約付きの生成(スキーマ)が動かなかったモデル([LoadedEngine.modelKey])。アプリを起動し直すまで覚える */
    private val schemaUnsupportedModels: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * 中断を頼んだ生成が終わるのを待つ。終わる前に次の生成を始めると、2つの生成が同時に動いてメモリを2倍使い
     * (端末内 AI のモデルは 1 つの生成で 1.6GB ほど使う)、遅い端末ではアプリごと止められるため、終わりの知らせまで待つ。
     * LiteRT-LM はプロンプトの読み込みの途中では止まらず、読み込みが終わってから止まる(遅い端末では1分以上かかる)。
     * 知らせが来ないエンジンの不具合で止まり続けないよう、[CANCEL_WAIT_MS] で待つのをやめる。
     */
    private suspend fun awaitFinished(finished: CompletableDeferred<Throwable?>) {
        if (withTimeoutOrNull(CANCEL_WAIT_MS) { finished.await() } == null && !finished.isCompleted) {
            Timber.w("端末内 AI の生成の終わりを待ちきれなかった")
        }
    }

    private class GenerationStats(val outChars: Int, val runaway: Boolean, val firstOutputNanos: Long)

    /** 1回の生成の所要時間・最初の出力までの時間(プロンプトの処理)・出力の速さ(文字/秒)を記録する。 */
    private fun logGeneration(
        label: String,
        backend: EngineBackend,
        promptChars: Int,
        stats: GenerationStats,
        startNanos: Long,
        endNanos: Long,
        bookmarkId: Long?,
        timedOut: Boolean
    ) {
        val totalMs = (endNanos - startNanos) / NANOS_PER_MILLI
        val detail = buildString {
            append("$label backend=$backend prompt=${promptChars}ch out=${stats.outChars}ch")
            if (stats.firstOutputNanos != 0L) {
                append(" firstOutput=${(stats.firstOutputNanos - startNanos) / NANOS_PER_MILLI}ms")
                val decodeMs = (endNanos - stats.firstOutputNanos) / NANOS_PER_MILLI
                if (decodeMs > 0) append(" decode=%.1fch/s".format(java.util.Locale.ROOT, stats.outChars * 1000.0 / decodeMs))
            }
            if (timedOut) append(" TIMEOUT")
            if (stats.runaway) append(" RUNAWAY")
        }
        AiPerfLog.record("llm.generate", totalMs, detail, bookmarkId)
    }

    override suspend fun analyze(
        input: AnalysisInput,
        existingTags: List<String>,
        scope: AnalysisScope
    ): BookmarkAnalysis = analyze(null, input, existingTags, scope)

    /** [modelId] のモデルでの [analyze]。 */
    private suspend fun analyze(
        modelId: String?,
        input: AnalysisInput,
        existingTags: List<String>,
        scope: AnalysisScope
    ): BookmarkAnalysis {
        // 端末内 AI はファイルそのものを読めない。取り出したテキストも無ければ、ファイル名だけの要約は作らない
        if (input.attachment != null && input.text.isBlank()) throw NothingToAnalyzeException()
        // 上限を超える本文は、部分ごとに要約してからまとめる
        val body = digester.prepare(input.text) { part ->
            val prompt = listOf(
                AiPrompts.partInstructions(input.kind, LONG_TEXT.noteMaxChars),
                "",
                AiPrompts.partInput(input.title, part)
            ).joinToString("\n")
            // メモは壊れていても緩く読めるため、作り直させない(部分ごとに数分かかるため)
            val response = generateAnalysis(
                modelId, prompt, "part ${part.number}/${part.total}", PART_MAX_OUTPUT_CHARS, ::partTimeoutMillis,
                responseSchema = AnalysisSchema.notes(maxChars = LONG_TEXT.noteMaxChars * SCHEMA_LENGTH_FACTOR)
            )
            AnalysisParser.parseNotes(response)
        }
        val digest = body is PreparedBody.Digest
        val isLong = digest || input.text.length > AiPrompts.LONG_TEXT_CHARS
        val prompt = buildAnalyzePrompt(input, body.text, isLong, digest, existingTags, scope)
        val schema = AnalysisSchema.analysis(
            scope,
            AnalysisSchema.Limits(summaryMaxChars = AiPrompts.summaryMaxChars(input.kind, isLong) * SCHEMA_LENGTH_FACTOR)
        )
        // 形の崩れた出力(要約が空など)は、直す指示を付けて作り直させる(端末内は1回に数分かかるため、作り直しは少なく)
        val response = StructuredOutput.generateWithRepair(
            maxRepairs = MAX_REPAIRS,
            label = "端末内 AI",
            validate = { StructuredOutput.problemOf(it, scope) }
        ) { problem, _ ->
            if (problem == null) {
                generateAnalysis(modelId, prompt, "final", responseSchema = schema)
            } else {
                generateAnalysis(
                    modelId, prompt + "\n\n" + AiPrompts.repairInstructions(problem, scope), "final repair", responseSchema = schema
                )
            }
        }
        // 小さいモデルは指示の例やカテゴリの候補の語をタグに写すため、保存内容に出てくるタグだけを残す
        return AnalysisParser.keepMentionedTags(AnalysisParser.parse(response, input.combinedText()), input.combinedText())
    }

    override suspend fun chat(
        userMessage: String,
        context: List<String>,
        history: List<ChatMessage>
    ): String = chatStream(userMessage, context, history).fold("") { acc, chunk -> acc + chunk }.trim()

    /** 資料の選択など、画面で結果を待つ JSON の生成。背景の解析より先に動かす。 */
    override suspend fun complete(prompt: String): String = complete(null, prompt)

    private suspend fun complete(modelId: String?, prompt: String): String = asInteractive {
        generateAnalysisOnce(modelId, prompt, "complete", ANALYZE_MAX_OUTPUT_CHARS, { ANALYZE_TIMEOUT_MS }, preemptible = false)!!
    }

    /**
     * 回答を増分で流す。背景の解析より先に動かす([asInteractive])。
     * - 入力が文脈長を超えたと言われたら(文字数の見積もりが外れた)、文脈と履歴を減らして組み立て直す
     * - 何も出力されなかったらやり直す(小型モデルはまれに、すぐ終わりの印を出す)。試すのは最初の1回を含めて [MAX_CHAT_ATTEMPTS] 回まで
     * - 同じ行の繰り返し・長すぎる出力は [ChatOutputGuard] で止め、そこまでを回答にする
     * 収集側のキャンセルで生成を中断する。
     */
    override fun chatStream(
        userMessage: String,
        context: List<String>,
        history: List<ChatMessage>
    ): Flow<String> = chatStream(null, userMessage, context, history)

    /** [modelId] のモデルでの [chatStream]。 */
    private fun chatStream(
        modelId: String?,
        userMessage: String,
        context: List<String>,
        history: List<ChatMessage>
    ): Flow<String> = flow {
        asInteractive {
            var maxChars = LocalChatPrompt.MAX_PROMPT_CHARS
            for (attempt in 1..MAX_CHAT_ATTEMPTS) {
                var emitted = false
                try {
                    val guard = ChatOutputGuard()
                    streamOnce(modelId, LocalChatPrompt.build(userMessage, context, history, maxChars))
                        .transformWhile { chunk ->
                            emit(guard.accept(chunk))
                            !guard.stopped
                        }
                        .collect { text ->
                            if (text.isNotBlank()) emitted = true
                            if (text.isNotEmpty()) emit(text)
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (emitted || attempt == MAX_CHAT_ATTEMPTS || !isInputTooLong(e)) throw e
                    maxChars = maxChars * PROMPT_SHRINK_PERCENT / 100
                    Timber.w(e, "端末内 AI の入力が長すぎたため、文脈を減らしてやり直す: %d 文字まで", maxChars)
                    continue
                }
                if (emitted) return@asInteractive
                Timber.w("端末内 AI の回答が空だったため、やり直す(%d 回目)", attempt)
            }
        }
    }

    /**
     * [block] を、画面で結果を待つ生成として動かす。動いている間は背景の解析の生成を中断させ、新しく始めさせない。
     */
    private suspend fun <T> asInteractive(block: suspend () -> T): T {
        interactiveCount.update { it + 1 }
        try {
            preemptBackground?.invoke()
            return block()
        } finally {
            interactiveCount.update { it - 1 }
        }
    }

    /** 生成の増分を流す。収集側のキャンセルで生成を中断する。 */
    private fun streamOnce(modelId: String?, prompt: String): Flow<String> = callbackFlow {
        withEngine(modelId) { loaded ->
            val finished = CompletableDeferred<Throwable?>()
            val generation = loaded.engine.start(
                prompt,
                GenerationKind.CHAT,
                onPartial = { partial -> trySend(partial) },
                onDone = { error ->
                    finished.complete(error)
                    close(error)
                }
            )
            try {
                awaitClose()
            } finally {
                // 途中で止められた場合は生成を中断し、終わるのを待ってから閉じる
                if (!finished.isCompleted) generation.cancel()
                withContext(NonCancellable) { awaitFinished(finished) }
                generation.close()
            }
        }
    }.buffer(Channel.UNLIMITED).flowOn(dispatcherProvider.default)

    private fun buildAnalyzePrompt(
        input: AnalysisInput,
        body: String,
        isLong: Boolean,
        digest: Boolean,
        existingTags: List<String>,
        scope: AnalysisScope
    ): String = listOf(
        AiPrompts.compactAnalyzeInstructions(
            input.kind,
            // 小さいモデルは渡した既存のタグを内容に関係なく写すため、タイトルか本文に出てくるものだけを渡す
            // (続けて保存したブックマークのタグが、関係ないブックマークに付くのを防ぐ)
            AiPrompts.existingTagsMentionedIn(existingTags, input.combinedText()),
            summaryMaxChars = AiPrompts.summaryMaxChars(input.kind, isLong),
            scope = scope,
            digest = digest
        ),
        "",
        AiPrompts.analyzeInput(input.title, body, MAX_INPUT_CHARS)
    ).joinToString("\n")

    companion object {
        /** 入力と出力の合計トークン数の上限。プロンプトの文字数の予算は LocalChatPrompt を参照 */
        private const val MAX_TOKENS = 4096

        /** チャットの生成を試す回数(入力が長すぎた・回答が空だったときのやり直しを含む) */
        private const val MAX_CHAT_ATTEMPTS = 3

        /** 入力が長すぎたときに、プロンプトの文字数の予算を何 % に減らすか */
        private const val PROMPT_SHRINK_PERCENT = 60

        private const val MAX_INPUT_CHARS = 2000

        private const val NANOS_PER_MILLI = 1_000_000L

        /** 解析の出力の形が正しくなかったときに作り直させる回数の上限(端末内は1回の生成に数分かかるため1回) */
        private const val MAX_REPAIRS = 1

        /**
         * 制約付きの生成で、文字列の長さの上限を指示の文字数の何倍にするか。普通の出力は切らず、
         * 繰り返しに陥って伸び続ける出力だけを、その長さで閉じさせる
         */
        private const val SCHEMA_LENGTH_FACTOR = 3

        /**
         * モデルの書式の代わりに使う会話の書式。旧形式の .task の Gemma だけ Gemma の書式を明示し、
         * ほかはモデルのファイルに入っている書式を使う(null)。
         */
        internal fun chatTemplateFor(model: LocalModelInfo): String? =
            if (model.isLegacyTaskFormat && model.isGemma) LiteRtLmTextEngine.GEMMA_CHAT_TEMPLATE else null

        /** 生成の中断を頼んでから、終わりの知らせを待つ時間の上限(プロンプトの読み込みが終わるまでは止まらないため長め) */
        private const val CANCEL_WAIT_MS = 10 * 60 * 1000L

        /** 演算装置(GPU / CPU)の記録のファイル名。`filesDir/models/` に置く */
        private const val BACKEND_FILE_NAME = "local_llm_backend.json"

        /**
         * 最後に使ってからこの時間が経ったら、エンジン(モデルのメモリ)を解放する。
         * 連続して保存したときの再読み込みは避けつつ、使っていないのにメモリを持ち続けない長さ。
         */
        private const val IDLE_RELEASE_MS = 2 * 60 * 1000L

        /**
         * 解析(要約・タグ)の生成にかける時間の上限。速い端末では CPU でも 1 分程度で終わるが、遅い端末(Snapdragon 665 の
         * Xperia 10 II で Qwen3 0.6B)ではプロンプトの読み込みだけで 2,000 文字あたり約 80 秒、出力は 1 文字あたり約 0.3 秒かかる。
         * 繰り返しに陥った生成は出力の文字数の上限([ANALYZE_MAX_OUTPUT_CHARS])で止めるため、時間の上限は遅い端末に合わせて長めにする。
         * 端末内 AI の解析はフォアグラウンドで動かす(BookmarkAiProcessingWorker)ため、WorkManager の 10 分の上限は受けにくい。
         */
        private const val ANALYZE_TIMEOUT_MS = 6 * 60 * 1000L

        /**
         * 解析の出力の文字数の上限。要約・タグ・カテゴリの JSON は数百文字で、閉じる前にこれを超えるのは繰り返しに陥ったとき。
         * 上限の時間([ANALYZE_TIMEOUT_MS])まで待たずに打ち切る(日本語は 1 文字 1 トークン前後のため、上限はおよそ 2,000 トークン)
         */
        private const val ANALYZE_MAX_OUTPUT_CHARS = 2_000

        /** 部分要約の出力の文字数の上限。メモは [LongTextConfig.noteMaxChars](300)文字が目安の短い JSON */
        private const val PART_MAX_OUTPUT_CHARS = 800

        /**
         * 長い本文の分割要約。入力の上限(2,000 文字)ごとに最大 3 部分(約 6,000 文字。超える分は均等に間引く)。
         * 部分要約1回の時間の上限は [partTimeoutMillis](GPU 3 分 / CPU 5 分)。遅い端末では時間の目安(4 分)を超えた部分は飛ばす。
         * 速い端末(CPU で部分要約1回 1 分程度)では、部分要約 3 回 + まとめで数分に収まる。
         * (測った速度に応じて時間の目安を変えることも考えられるが、まず `AiPerf` のログで実測してから調整する)
         */
        private val LONG_TEXT = LongTextConfig(
            chunkChars = MAX_INPUT_CHARS,
            maxChunks = 3,
            noteMaxChars = 300,
            timeBudgetMillis = 4 * 60 * 1000L
        )

        /** 最後に作られたインスタンス([trimMemory] の宛先)。アプリで1つだけ(@Singleton)のため、弱参照で持つ。 */
        @Volatile
        private var latest: WeakReference<LocalLlmProvider>? = null

        /**
         * `Application.onTrimMemory` から呼ぶ。メモリが逼迫したとき、使っていない端末内モデルを手放す。
         * まだ作られていなければ(モデルを読み込んでいなければ)何もしない。インスタンスを新たに作らないため静的に受ける。
         */
        fun trimMemory(level: Int) {
            latest?.get()?.releaseOnTrim(level)
        }
    }
}
