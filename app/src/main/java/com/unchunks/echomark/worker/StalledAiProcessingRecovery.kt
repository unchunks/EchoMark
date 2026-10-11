package com.unchunks.echomark.worker

import com.unchunks.echomark.di.DispatcherProvider
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
 * アプリの起動時に、処理待ち・処理中のまま対応するワークが無くなったブックマークの AI 処理を積み直す
 * (取り消し・中断の行き違いなどで、表示が「AI処理中…」「AI処理待ち」のまま終わらなくなるのを防ぐ)。
 */
@Singleton
class StalledAiProcessingRecovery @Inject constructor(
    // アプリ起動時(メインスレッド)に DB などを作らないよう、使うときに取り出す
    private val bookmarkRepository: Lazy<BookmarkRepository>,
    dispatcherProvider: DispatcherProvider
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)

    /** 確認と積み直しをバックグラウンドで行う(Application.onCreate から1回呼ぶ)。 */
    fun start() {
        scope.launch {
            try {
                val count = bookmarkRepository.get().enqueueStalledProcessing()
                if (count > 0) Timber.i("止まっていた AI 処理を積み直した: $count 件")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "止まっている AI 処理の確認に失敗")
            }
        }
    }
}
