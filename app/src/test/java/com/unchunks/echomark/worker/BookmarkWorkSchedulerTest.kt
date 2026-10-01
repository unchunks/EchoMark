package com.unchunks.echomark.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.unchunks.echomark.testing.initTestWorkManager
import com.unchunks.echomark.testing.statesByTag
import com.unchunks.echomark.testing.statesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookmarkWorkSchedulerTest {

    private lateinit var workManager: WorkManager
    private lateinit var scheduler: BookmarkWorkScheduler

    @Before
    fun setUp() {
        workManager = initTestWorkManager(ApplicationProvider.getApplicationContext<Context>())
        scheduler = BookmarkWorkScheduler(workManager)
    }

    private fun unfinished(id: Long) =
        workManager.statesOf(BookmarkWorkScheduler.uniqueWorkName(id)).filterNot { it.isFinished }

    @Test
    fun 同じブックマークの処理は新しい依頼で置き換わる() {
        scheduler.enqueue(1L, fetchContent = true)
        scheduler.enqueue(1L, fetchContent = false)

        assertEquals(1, unfinished(1L).size)
    }

    @Test
    fun 取り消しは対象のブックマークだけ() {
        scheduler.enqueue(1L, fetchContent = true)
        scheduler.enqueue(2L, fetchContent = true)

        scheduler.cancel(1L)

        assertTrue(unfinished(1L).isEmpty())
        assertEquals(2, unfinished(2L).size)
    }

    @Test
    fun すべて取り消す() {
        scheduler.enqueue(1L, fetchContent = true)
        scheduler.enqueue(2L, fetchContent = false)

        scheduler.cancelAll()

        val states = workManager.statesByTag(BookmarkWorkScheduler.TAG)
        assertEquals(3, states.size)
        assertTrue(states.toString(), states.all { it == WorkInfo.State.CANCELLED })
    }
}
