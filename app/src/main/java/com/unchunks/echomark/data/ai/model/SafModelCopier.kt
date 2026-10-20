package com.unchunks.echomark.data.ai.model

import android.content.Context
import android.net.Uri
import com.unchunks.echomark.data.storage.allocatableBytes
import com.unchunks.echomark.data.storage.queryOpenableInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * SAF で選んだモデルファイルを読む処理。オンデバイス LLM([ModelManager])と
 * 埋め込みモデル([EmbeddingModelManager])の取り込みで共通に使う。
 */
internal class SafModelCopier(private val context: Context) {

    /** 表示名とサイズ(不明なら -1)。 */
    fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        val info = context.contentResolver.queryOpenableInfo(uri)
        return (info.displayName ?: uri.lastPathSegment.orEmpty()) to (info.sizeBytes ?: -1L)
    }

    /** [dir] に確保できる容量。消去可能なキャッシュ分も含めて見積もる。 */
    fun allocatableBytes(dir: File): Long = context.allocatableBytes(dir)

    /**
     * [uri] の中身を [dest] へコピーし、コピーしたバイト数を返す。
     * 一定量ごと([PROGRESS_STEP_BYTES])と最後に、コピー済みのバイト数を [onProgress] で知らせる。
     */
    suspend fun copyWithProgress(uri: Uri, dest: File, onProgress: (copiedBytes: Long) -> Unit): Long {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("openInputStream returned null")
        var copied = 0L
        var lastReported = 0L
        input.use { src ->
            FileOutputStream(dest).use { out ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = src.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    copied += read
                    // 状態更新が多すぎないよう、一定量ごとに通知する
                    if (copied - lastReported >= PROGRESS_STEP_BYTES) {
                        lastReported = copied
                        onProgress(copied)
                    }
                }
                out.fd.sync()
            }
        }
        onProgress(copied)
        return copied
    }

    private companion object {
        const val BUFFER_SIZE = 256 * 1024
        const val PROGRESS_STEP_BYTES = 8L * 1024 * 1024
    }
}
