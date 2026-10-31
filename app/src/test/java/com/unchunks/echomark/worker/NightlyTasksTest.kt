package com.unchunks.echomark.worker

import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeBookmarkRepository
import dagger.Lazy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 夜間の仕事の時刻と、夜間にまとめて処理する仕事(#9)。 */
class NightlyTasksTest {

    private val tokyo = ZoneId.of("Asia/Tokyo")

    @Test
    fun 次に訪れる指定の時刻までの遅延() {
        val now = ZonedDateTime.of(2026, 10, 27, 23, 30, 0, 0, tokyo)

        assertEquals(150 * 60_000L, NightlyTaskScheduler.initialDelayMillis(now, hour = 2))
        // 今日のその時刻を過ぎていれば翌日
        assertEquals(22 * 3_600_000L + 30 * 60_000L, NightlyTaskScheduler.initialDelayMillis(now, hour = 22))
        // ちょうど今なら 1 日後
        assertEquals(
            24 * 3_600_000L,
            NightlyTaskScheduler.initialDelayMillis(ZonedDateTime.of(2026, 10, 27, 2, 0, 0, 0, tokyo), hour = 2)
        )
    }

    @Test
    fun 夜間にまとめて処理する設定のときだけ_待っていたものを夜間の列に積む() = runBlocking {
        val settings = FakeAppSettingsRepository()
        val repository = FakeBookmarkRepository()
        val task = NightlyProcessingTask(settings, Lazy { repository })

        task.run()
        assertTrue(repository.waitingProcessingCalls.isEmpty())

        settings.updateProcessingSettings { it.copy(nightlyBatch = true) }
        task.run()
        assertEquals(listOf(true), repository.waitingProcessingCalls)
    }
}
