package com.unchunks.echomark.ui.widget

import com.unchunks.echomark.domain.bookmark.model.ProcessingQueue
import com.unchunks.echomark.domain.bookmark.model.StageDurationKey
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.ProcessingStatsRepository
import dagger.Lazy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ウィジェットに出す AI 処理の進み具合を流す。処理中・処理待ちがある間だけ [tickMillis] ごとに見込み直す
 * (段が進まなくても、経過に合わせて残り時間が減るように)。何も無くなったら null を流して、時計も止める。
 */
@Singleton
class WidgetProcessingSource @Inject constructor(
    // アプリ起動時(メインスレッド)に DB などを作らないよう、使うときに取り出す
    private val bookmarkRepository: Lazy<BookmarkRepository>,
    private val processingStats: Lazy<ProcessingStatsRepository>
) {
    fun observe(clock: () -> Long = System::currentTimeMillis, tickMillis: Long = TICK_MILLIS): Flow<WidgetProcessing?> {
        val repository = bookmarkRepository.get()
        return summaries(
            queue = repository.observeProcessingQueue(),
            stageProgress = repository::observeStageProgress,
            averages = processingStats.get().averageStageDurations,
            clock = clock,
            tickMillis = tickMillis
        )
    }

    companion object {
        /** 見込み直す間隔。ウィジェットの描き直しは重いので、分単位で足りる */
        const val TICK_MILLIS = 60_000L

        @OptIn(ExperimentalCoroutinesApi::class)
        internal fun summaries(
            queue: Flow<ProcessingQueue>,
            stageProgress: (Long) -> Flow<Float?>,
            averages: Flow<Map<StageDurationKey, Long>>,
            clock: () -> Long,
            tickMillis: Long
        ): Flow<WidgetProcessing?> =
            queue.flatMapLatest { current ->
                if (current.running.isEmpty() && current.waiting.isEmpty()) {
                    flowOf(null)
                } else {
                    val fractions = stageFractions(current.running.map { it.id }, stageProgress)
                    val ticks = flow {
                        while (true) {
                            emit(Unit)
                            delay(tickMillis)
                        }
                    }
                    combine(
                        fractions,
                        averages.catch { emit(emptyMap()) }.onStart { emit(emptyMap()) },
                        ticks
                    ) { fraction, average, _ -> WidgetProcessingSummary.summarize(current, clock(), fraction, average) }
                }
            }.distinctUntilChanged()

        /** 処理中のものの、今の段の進み具合(報告している段だけ)。 */
        private fun stageFractions(ids: List<Long>, stageProgress: (Long) -> Flow<Float?>): Flow<Map<Long, Float>> =
            if (ids.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(ids.map { id -> stageProgress(id).map { id to it } }) { pairs ->
                    pairs.mapNotNull { (id, progress) -> progress?.let { id to it } }.toMap()
                }
            }
    }
}
