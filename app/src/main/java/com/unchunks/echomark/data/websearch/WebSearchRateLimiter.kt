package com.unchunks.echomark.data.websearch

import com.unchunks.echomark.domain.websearch.WebSearchException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Web 検索の間を空ける。Brave Search API は1秒の幅で回数を数え、超えると 429 で断る(プランによっては1秒に1回)。
 * どのプランでも断られないよう、アプリ全体で [minIntervalMillis] に1回までにする。
 * 429 を受けたら、応答のヘッダーが示す時間([pauseFor])まで次の検索を待たせる。待ち時間が [maxWaitMillis] を超えるなら、
 * 待たずに [WebSearchException.RateLimited] で知らせる(チャットの回答を長く止めないため)。
 */
@Singleton
class WebSearchRateLimiter internal constructor(
    private val minIntervalMillis: Long,
    private val maxWaitMillis: Long,
    private val clock: () -> Long
) {
    @Inject
    constructor() : this(MIN_INTERVAL_MILLIS, MAX_WAIT_MILLIS, System::currentTimeMillis)

    private val mutex = Mutex()

    @Volatile
    private var nextAllowedAt = 0L

    /** 次の検索をしてよくなるまで待つ。待ち時間が長すぎるなら [WebSearchException.RateLimited] */
    suspend fun acquire() = mutex.withLock {
        val wait = nextAllowedAt - clock()
        if (wait > maxWaitMillis) throw WebSearchException.RateLimited((wait + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND)
        if (wait > 0) delay(wait)
        nextAllowedAt = clock() + minIntervalMillis
    }

    /** 429 を受けた。[millis] の間は次の検索をしない */
    @Synchronized
    fun pauseFor(millis: Long) {
        nextAllowedAt = maxOf(nextAllowedAt, clock() + millis)
    }

    companion object {
        /** 検索の間隔(1秒に1回のプランでも、時計のずれで断られないよう少し空ける) */
        const val MIN_INTERVAL_MILLIS = 1_100L

        /** これより長く待つなら、待たずに失敗にする */
        const val MAX_WAIT_MILLIS = 5_000L

        private const val MILLIS_PER_SECOND = 1_000L
    }
}
