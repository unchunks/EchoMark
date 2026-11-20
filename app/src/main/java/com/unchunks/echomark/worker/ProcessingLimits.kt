package com.unchunks.echomark.worker

import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.CLOUD_AI_ENABLED
import com.unchunks.echomark.domain.repository.LlmBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 重い段(中身の取り出し・AI 処理)を同時に動かす数を、設定([com.unchunks.echomark.domain.repository.ProcessingSettings.maxConcurrent])
 * までに抑える(#70)。WorkManager はブックマークごとの処理を並行して動かすため、多く保存すると端末内 AI が
 * 同時に何件も動いてメモリ・発熱が増える。上限に達していれば、空くまで待つ(その間ブックマークは処理待ちのまま)。
 * 上限はアプリのプロセスの中だけで数える(ワークは同じプロセスで動く)。
 */
@Singleton
class ProcessingConcurrencyLimiter @Inject constructor(
    private val appSettings: AppSettingsRepository
) {
    private val running = MutableStateFlow(0)
    private val mutex = Mutex()

    /** 今動いている数 */
    val runningCount: Int get() = running.value

    suspend fun <T> withPermit(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            running.update { it - 1 }
        }
    }

    private suspend fun acquire() {
        while (true) {
            val limit = appSettings.processingSettings.first().maxConcurrent.coerceAtLeast(1)
            val acquired = mutex.withLock {
                if (running.value < limit) {
                    running.update { it + 1 }
                    true
                } else {
                    false
                }
            }
            if (acquired) return
            // 空きが出るまで待ってから、もう一度上限を読み直して試す
            running.first { it < limit }
        }
    }
}

/**
 * 処理でクラウド API を呼ぶ回数を、設定([com.unchunks.echomark.domain.repository.ProcessingSettings.apiRequestsPerMinute])の
 * 1 分あたりの上限までに抑える(#70)。上限に達していれば、いちばん古い呼び出しから 1 分経つまで待つ。
 * 端末内 AI だけで処理する設定・上限なし(0)のときは待たない。
 */
@Singleton
class ApiRateLimiter @Inject constructor(
    private val appSettings: AppSettingsRepository
) {
    /** 今の時刻(テストで差し替える) */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    private val mutex = Mutex()
    private val recent = ArrayDeque<Long>()

    /** タグ付け・要約のどちらかにクラウド API を使う設定なら、呼び出しの枠が空くまで待ってから枠を取る */
    suspend fun acquireIfApi() {
        if (usesApi()) acquire()
    }

    /** 呼び出しの枠が空くまで待ってから枠を取る(上限なしなら待たない) */
    internal suspend fun acquire() {
        val limit = appSettings.processingSettings.first().apiRequestsPerMinute
        if (limit <= 0) return
        while (true) {
            val waitMillis = mutex.withLock {
                val now = clock()
                while (recent.isNotEmpty() && now - recent.first() >= WINDOW_MILLIS) recent.removeFirst()
                if (recent.size < limit) {
                    recent.addLast(now)
                    0L
                } else {
                    WINDOW_MILLIS - (now - recent.first())
                }
            }
            if (waitMillis <= 0) return
            delay(waitMillis)
        }
    }

    private suspend fun usesApi(): Boolean {
        if (!CLOUD_AI_ENABLED) return false
        val tasks = appSettings.aiTaskSettings.first()
        return listOf(AiTask.TAGGING, AiTask.SUMMARY).any { tasks[it]?.backend == LlmBackend.API }
    }

    private companion object {
        const val WINDOW_MILLIS = 60_000L
    }
}
