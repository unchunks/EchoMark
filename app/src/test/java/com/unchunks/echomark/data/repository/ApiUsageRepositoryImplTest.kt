package com.unchunks.echomark.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.llm.ApiProvider
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.entity.ApiUsageEntity
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.usage.ApiUsageRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApiUsageRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ApiUsageRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = ApiUsageRepositoryImpl(db.apiUsageDao(), TestDispatcherProvider(Dispatchers.Unconfined))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun record(createdAt: Long, model: String = "claude-haiku-5-5", input: Long = 100, output: Long = 20) =
        ApiUsageRecord(createdAt, ApiProvider.CLAUDE, model, AiTask.SUMMARY, input, output)

    @Test
    fun 記録した使用量を提供元_モデル_用途_トークン数つきで読める() = runBlocking {
        repository.record(
            ApiUsageRecord(1_000, ApiProvider.GEMINI, "gemini-3.5-flash-lite", AiTask.CHAT, inputTokens = 300, outputTokens = 45)
        )

        assertEquals(
            listOf(ApiUsageRecord(1_000, ApiProvider.GEMINI, "gemini-3.5-flash-lite", AiTask.CHAT, 300, 45)),
            repository.observeSince(0).first()
        )
    }

    @Test
    fun 指定した時刻以降を古い順に読む() = runBlocking {
        repository.record(record(3_000))
        repository.record(record(1_000))
        repository.record(record(2_000))

        assertEquals(listOf(2_000L, 3_000L), repository.observeSince(2_000).first().map { it.createdAt })
    }

    @Test
    fun 約13か月より古い記録は記録のたびに消す() = runBlocking {
        val now = 1_000L * ApiUsageRepositoryImpl.RETENTION_MILLIS
        repository.record(record(now - ApiUsageRepositoryImpl.RETENTION_MILLIS - 1))
        repository.record(record(now - ApiUsageRepositoryImpl.RETENTION_MILLIS + 1))

        repository.record(record(now))

        assertEquals(
            listOf(now - ApiUsageRepositoryImpl.RETENTION_MILLIS + 1, now),
            repository.observeSince(0).first().map { it.createdAt }
        )
    }

    @Test
    fun 読めない提供元や用途の記録は飛ばす() = runBlocking {
        db.apiUsageDao().insert(ApiUsageEntity(createdAt = 1, provider = "FUTURE", model = "m", task = "SUMMARY", inputTokens = 1, outputTokens = 1))
        db.apiUsageDao().insert(ApiUsageEntity(createdAt = 2, provider = "CLAUDE", model = "m", task = "FUTURE", inputTokens = 1, outputTokens = 1))
        repository.record(record(3))

        assertEquals(listOf(3L), repository.observeSince(0).first().map { it.createdAt })
    }

    @Test
    fun 全データの削除で使用量の記録も消える() = runBlocking {
        repository.record(record(1))

        db.backupDao().deleteAll()

        assertTrue(repository.observeSince(0).first().isEmpty())
    }
}
