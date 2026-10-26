package com.unchunks.echomark.data.ai.local

import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * 端末内 LLM の推論に使う演算装置。MediaPipe の `LlmInference.Backend` の GPU / CPU に対応する
 * (MediaPipe に依存しない純粋な型にして、選び方を単体テストできるようにしている)。
 */
internal enum class EngineBackend { GPU, CPU }

/**
 * 試す順序。GPU を使う設定([allowGpu])でなければ CPU だけ。
 * GPU を使う設定なら GPU を先に試して、作れなければ CPU にする。
 * 前に GPU が使えなかったと覚えていれば([remembered] が CPU)、毎回 GPU の失敗を待たずに CPU だけにする。
 * GPU が使えると覚えていても、ドライバの更新などで作れなくなる場合に備えて CPU を後に残す。
 */
internal fun backendOrder(remembered: EngineBackend?, allowGpu: Boolean = true): List<EngineBackend> =
    if (!allowGpu || remembered == EngineBackend.CPU) listOf(EngineBackend.CPU) else listOf(EngineBackend.GPU, EngineBackend.CPU)

/** モデルごとに動いた演算装置を覚えておく場所。 */
internal interface BackendStore {
    fun read(modelKey: String): EngineBackend?
    fun write(modelKey: String, backend: EngineBackend)

    /**
     * GPU を試している最中のモデル([writeGpuTrial] で書いたもの)。無ければ null。
     * GPU の初期化・最初の推論はネイティブのコードで落ちることがあり(例外にならずプロセスごと終了する)、
     * その場合はここに残ったままになるため、次の起動で「前回 GPU で落ちた」と分かる。
     */
    fun readGpuTrial(): String? = null

    /** GPU を試し始めるときにモデルを書き、うまくいった・やめたときに null で消す。 */
    fun writeGpuTrial(modelKey: String?) {}
}

/**
 * 動いた演算装置を [file] に小さな JSON で保存する(`{"key": "...", "backend": "CPU"}`。モデルは1つだけなので1件)。
 * モデルを取り込み直すと [modelKey] が変わるため、前のモデルの結果は使われない。
 * アプリのバックアップ対象外の `filesDir/models/` に置き、GPU の性能が違う別の端末へ復元されないようにする。
 * 読み書きの失敗は無視する(覚えていないだけで、毎回 GPU を試す)。
 */
internal class FileBackendStore(private val file: File) : BackendStore {
    override fun read(modelKey: String): EngineBackend? = try {
        if (!file.isFile) {
            null
        } else {
            val json = JSONObject(file.readText())
            if (json.getString("key") == modelKey) EngineBackend.valueOf(json.getString("backend")) else null
        }
    } catch (e: Exception) {
        Timber.w(e, "端末内モデルの演算装置の記録を読めない")
        null
    }

    override fun write(modelKey: String, backend: EngineBackend) {
        try {
            file.parentFile?.mkdirs()
            val json = JSONObject().put("key", modelKey).put("backend", backend.name)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(json.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            Timber.w(e, "端末内モデルの演算装置を記録できない")
        }
    }

    /** GPU を試している最中の印。[file] と同じ場所の別のファイルに、モデルのキーだけを書く */
    private val trialFile: File get() = File(file.parentFile, file.name + TRIAL_SUFFIX)

    override fun readGpuTrial(): String? = try {
        trialFile.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Timber.w(e, "端末内モデルの GPU の試行の記録を読めない")
        null
    }

    override fun writeGpuTrial(modelKey: String?) {
        try {
            if (modelKey == null) {
                trialFile.delete()
            } else {
                file.parentFile?.mkdirs()
                // ネイティブのクラッシュに備え、GPU を試す前に書き終えておく(プロセスが落ちてもページキャッシュは残る)
                trialFile.writeText(modelKey)
            }
        } catch (e: Exception) {
            Timber.w(e, "端末内モデルの GPU の試行を記録できない")
        }
    }

    private companion object {
        const val TRIAL_SUFFIX = ".gpu_trial"
    }
}

/**
 * エンジンを作るときの演算装置の選択。[backendOrder] の順に試し、最初に作れたものを使う。
 * 結果はこのインスタンスの中([memory])と [store] に覚え、次からは失敗した装置を試さない。
 * スレッドセーフではない(呼び出し側のミューテックスの中で使う)。
 */
internal class BackendSelector(private val store: BackendStore) {

    /** 作れたエンジンと、使った演算装置。 */
    class Created<T>(val engine: T, val backend: EngineBackend)

    private val memory = HashMap<String, EngineBackend>()

    /**
     * このプロセスで書いた GPU の試行の印。印がこれと同じなら、まだ推論していないだけ(アイドルの解放の後に作り直すなど)で、
     * 前のプロセスが落ちた跡ではない。
     */
    private var ownTrial: String? = null

    private fun remembered(modelKey: String): EngineBackend? = memory[modelKey] ?: store.read(modelKey)?.also { memory[modelKey] = it }

    /** GPU を使う設定で [factory] でエンジンを作る([create] の allowGpu = true と同じ)。 */
    fun <T> create(modelKey: String, factory: (EngineBackend) -> T): Created<T> = create(modelKey, true, factory)

    /**
     * [factory] でエンジンを作る。演算装置ごとに試し、失敗したら次へ進む。すべて失敗したら最後の失敗を投げる
     * (先に失敗したものは suppressed に付ける)。
     *
     * 失敗には Error も含める(GPU のライブラリが読めない UnsatisfiedLinkError など)。例外にならずにプロセスごと
     * 落ちる失敗(ネイティブのクラッシュ)に備え、GPU を試す前に印を残し([BackendStore.writeGpuTrial])、
     * 最初の推論が成功したら [confirmGpu] で消す。次に作るときに印が残っていれば、前回 GPU で落ちたとみなして
     * このモデルは以後 CPU だけを使う(同じクラッシュを繰り返さない)。
     *
     * @param allowGpu GPU を使う設定か。false なら CPU だけを試す
     */
    fun <T> create(modelKey: String, allowGpu: Boolean, factory: (EngineBackend) -> T): Created<T> {
        recoverFromCrashedGpuTrial(modelKey)
        val remembered = remembered(modelKey)
        var failure: Throwable? = null
        for (backend in backendOrder(remembered, allowGpu)) {
            if (backend == EngineBackend.GPU) setTrial(modelKey)
            try {
                val engine = factory(backend)
                if (backend != remembered) remember(modelKey, backend)
                return Created(engine, backend)
            } catch (e: Throwable) {
                if (backend == EngineBackend.GPU) setTrial(null)
                Timber.w(e, "端末内モデルを $backend で読み込めない")
                failure?.let { e.addSuppressed(it) }
                failure = e
            }
        }
        throw failure ?: IllegalStateException("試す演算装置がありません")
    }

    /** GPU で作ったエンジンで最初の推論に成功した。GPU を試している印を消す */
    fun confirmGpu() = setTrial(null)

    /** GPU で作れたが推論に失敗したときなど、以後は CPU を使う。 */
    fun demoteToCpu(modelKey: String) {
        remember(modelKey, EngineBackend.CPU)
        setTrial(null)
    }

    private fun setTrial(modelKey: String?) {
        ownTrial = modelKey
        store.writeGpuTrial(modelKey)
    }

    /**
     * 前のプロセスが GPU を試している最中に終了していれば(印が残っていれば)、そのモデルは以後 CPU を使う。
     * 印が別のモデルのもの(取り込み直した)なら消すだけ。
     */
    private fun recoverFromCrashedGpuTrial(modelKey: String) {
        val trial = store.readGpuTrial() ?: return
        if (trial == ownTrial) return
        if (trial == modelKey) {
            Timber.w("前回、端末内モデルを GPU で動かしている最中にアプリが終了したため、以後は CPU を使う")
            remember(modelKey, EngineBackend.CPU)
        }
        setTrial(null)
    }

    private fun remember(modelKey: String, backend: EngineBackend) {
        memory[modelKey] = backend
        store.write(modelKey, backend)
    }
}

/**
 * [ComponentCallbacks2.onTrimMemory][android.content.ComponentCallbacks2.onTrimMemory] の [level] で、
 * 使っていない端末内モデルを手放すか。メモリが逼迫している(RUNNING_LOW=10 / RUNNING_CRITICAL=15)か、
 * アプリのプロセスがキャッシュされて強制終了されやすくなった(BACKGROUND=40 以上)とき。
 * UI_HIDDEN(20)は画面が隠れただけでメモリの逼迫ではない(バックグラウンドのワーカーで推論を続けることが多い)ため含めない。
 */
internal fun shouldReleaseEngineOnTrim(level: Int): Boolean =
    level == TRIM_RUNNING_LOW || level == TRIM_RUNNING_CRITICAL || level >= TRIM_BACKGROUND

private const val TRIM_RUNNING_LOW = 10
private const val TRIM_RUNNING_CRITICAL = 15
private const val TRIM_BACKGROUND = 40

/** 長い本文の部分要約1回の時間の上限。GPU は速いので短く、CPU は遅い端末を見込んで長めにする。 */
internal fun partTimeoutMillis(backend: EngineBackend): Long = when (backend) {
    EngineBackend.GPU -> 60 * 1000L
    EngineBackend.CPU -> 90 * 1000L
}

/**
 * 入力が文脈長(エンジンの maxTokens)を超えたときの MediaPipe の失敗か。プロンプトは文字数でトークン数を見積もっているため、
 * 1 文字が複数のトークンになる文字(絵文字・まれな漢字など)が多いと超えることがある。
 * 例: 「Input is too long for the model to process: current_step(0) + input_size(4210) was not less than maxTokens(4096).」
 */
internal fun isInputTooLong(e: Throwable): Boolean =
    generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH).any { it.message?.contains("Input is too long", ignoreCase = true) == true }

private const val MAX_CAUSE_DEPTH = 5
