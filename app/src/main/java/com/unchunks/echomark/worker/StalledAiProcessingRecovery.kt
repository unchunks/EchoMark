package com.unchunks.echomark.worker

import android.os.Process
import android.os.SystemClock
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.data.ai.AiPrewarmer
import com.unchunks.echomark.domain.repository.BookmarkRepository
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * アプリの起動時に、止まったままのブックマークの処理を直す
 * (プロセスの停止・取り消し・中断の行き違いなどで、表示が「処理中」「処理待ち」のまま終わらなくなるのを防ぐ)。
 * - 前のプロセスで始まった段のまま「処理中」のものは「処理待ち」に戻す(実行しているワークは無い)
 * - 「処理待ち」のまま対応するワークが無くなったものは積み直す
 */
@Singleton
class StalledAiProcessingRecovery @Inject constructor(
    // アプリ起動時(メインスレッド)に DB などを作らないよう、使うときに取り出す
    private val bookmarkRepository: Lazy<BookmarkRepository>,
    private val aiPrewarmer: Lazy<AiPrewarmer>,
    dispatcherProvider: DispatcherProvider
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)

    /** 確認と積み直しをバックグラウンドで行う(Application.onCreate から1回呼ぶ)。 */
    fun start() {
        val processStartedAt = processStartedAtMillis()
        scope.launch {
            try {
                val count = bookmarkRepository.get().enqueueStalledProcessing(processStartedAt)
                if (count > 0) {
                    Timber.i("止まっていた AI 処理を積み直した: $count 件")
                    // 積み直した処理がすぐ始まるため、端末内モデルを先に読み込んでおく
                    aiPrewarmer.get().prewarmLocalLlmIfUsed()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "止まっている AI 処理の確認に失敗")
            }
        }
    }

    /**
     * このプロセスが始まった日時(epoch ミリ秒)。ワークはアプリのプロセスで動くため、これより前に始まった段は
     * 前のプロセスのもので、もう実行されていない。
     */
    private fun processStartedAtMillis(): Long =
        System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
}
