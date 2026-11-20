package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.bookmark.model.StageDurationKey
import kotlinx.coroutines.flow.Flow

/**
 * 処理の段ごとの所要時間の記録(残り時間の目安に使う)。端末の性能・使う AI で大きく変わるため、端末の中で学ぶ。
 * 最近の処理を重く見た平均を持つ。
 */
interface ProcessingStatsRepository {
    /** 段・中身の種類ごとの平均の所要時間(ミリ秒)。記録の無いものは含まない。 */
    val averageStageDurations: Flow<Map<StageDurationKey, Long>>

    /** 最後まで終えた段の所要時間を記録する(中断・失敗した段は記録しない)。 */
    suspend fun recordStageDuration(key: StageDurationKey, millis: Long)
}
