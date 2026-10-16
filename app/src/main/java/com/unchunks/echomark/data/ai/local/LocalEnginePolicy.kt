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
 * 試す順序。基本は GPU を先に試して、作れなければ CPU にする。
 * 前に GPU が使えなかったと覚えていれば([remembered] が CPU)、毎回 GPU の失敗を待たずに CPU だけにする。
 * GPU が使えると覚えていても、ドライバの更新などで作れなくなる場合に備えて CPU を後に残す。
 */
internal fun backendOrder(remembered: EngineBackend?): List<EngineBackend> =
    if (remembered == EngineBackend.CPU) listOf(EngineBackend.CPU) else listOf(EngineBackend.GPU, EngineBackend.CPU)

/** モデルごとに動いた演算装置を覚えておく場所。 */
internal interface BackendStore {
    fun read(modelKey: String): EngineBackend?
    fun write(modelKey: String, backend: EngineBackend)
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

    private fun remembered(modelKey: String): EngineBackend? = memory[modelKey] ?: store.read(modelKey)?.also { memory[modelKey] = it }

    /**
     * [factory] でエンジンを作る。演算装置ごとに試し、失敗(例外)したら次へ進む。すべて失敗したら最後の失敗を投げる
     * (先に失敗したものは suppressed に付ける)。
     */
    fun <T> create(modelKey: String, factory: (EngineBackend) -> T): Created<T> {
        val remembered = remembered(modelKey)
        var failure: Exception? = null
        for (backend in backendOrder(remembered)) {
            try {
                val engine = factory(backend)
                if (backend != remembered) remember(modelKey, backend)
                return Created(engine, backend)
            } catch (e: Exception) {
                Timber.w(e, "端末内モデルを $backend で読み込めない")
                failure?.let { e.addSuppressed(it) }
                failure = e
            }
        }
        throw failure ?: IllegalStateException("試す演算装置がありません")
    }

    /** GPU で作れたが推論に失敗したときなど、以後は CPU を使う。 */
    fun demoteToCpu(modelKey: String) = remember(modelKey, EngineBackend.CPU)

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
