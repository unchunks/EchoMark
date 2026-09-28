package com.unchunks.echomark.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.unchunks.echomark.data.ai.model.ModelManager
import com.unchunks.echomark.data.ai.model.ModelSpec
import com.unchunks.echomark.data.ai.model.ModelSpecs
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.repository.BookmarkRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * モデルファイルをダウンロードする。
 * - 一時ファイル(.part)に書き込み、完了後に rename する
 * - 途中まで .part がある場合は Range ヘッダで続きから再開する
 * - Wi-Fi(UNMETERED)制約は ModelManager 側で付与する
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val modelManager: ModelManager,
    private val okHttpClient: OkHttpClient,
    private val bookmarkRepository: BookmarkRepository,
    private val dispatcherProvider: DispatcherProvider
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val spec = ModelSpecs.findById(inputData.getString(KEY_MODEL_ID)) ?: return Result.failure()
        if (modelManager.isAvailable(spec)) return Result.success()

        val result = withContext(dispatcherProvider.io) {
            try {
                download(spec)
            } catch (e: IOException) {
                Timber.w(e, "モデルのダウンロードに失敗(再試行): ${spec.id}")
                Result.retry()
            }
        }

        if (result is Result.Success) {
            // モデル待ちだったブックマークの AI 処理を再開する
            bookmarkRepository.enqueueWaitingModelProcessing()
        }
        return result
    }

    private suspend fun download(spec: ModelSpec): Result {
        val dest = modelManager.file(spec)
        val part = modelManager.partFile(spec)
        part.parentFile?.mkdirs()

        val resumeFrom = if (part.isFile) part.length() else 0L
        val requestBuilder = Request.Builder().url(spec.downloadUrl)
        if (resumeFrom > 0) requestBuilder.header("Range", "bytes=$resumeFrom-")

        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            when {
                response.code == 416 -> {
                    // 一時ファイルがサーバ側の内容と食い違っている。破棄してやり直す
                    part.delete()
                    return Result.retry()
                }
                response.code in 400..499 -> {
                    Timber.e("モデルのダウンロードが拒否された: HTTP ${response.code}")
                    return Result.failure()
                }
                !response.isSuccessful -> return Result.retry()
            }

            val body = response.body
            // 206 なら追記、200 ならサーバが Range を無視したので最初から書き直す
            val append = response.code == 206
            val startOffset = if (append) resumeFrom else 0L
            val total = if (body.contentLength() > 0) startOffset + body.contentLength() else spec.sizeBytes

            var written = startOffset
            var lastPercent = -1
            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val percent = (written * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                setProgress(workDataOf(KEY_PROGRESS to percent))
                            }
                        }
                    }
                }
            }
        }

        if (spec.sizeBytes > 0 && part.length() != spec.sizeBytes) {
            Timber.e("サイズ不一致: expected=${spec.sizeBytes}, actual=${part.length()}")
            part.delete()
            return Result.retry()
        }
        return finalize(part, dest)
    }

    private fun finalize(part: File, dest: File): Result {
        dest.delete()
        return if (part.renameTo(dest)) {
            Result.success()
        } else {
            Timber.e("モデルファイルの rename に失敗")
            Result.failure()
        }
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_PROGRESS = "progress"
        private const val BUFFER_SIZE = 64 * 1024
    }
}
