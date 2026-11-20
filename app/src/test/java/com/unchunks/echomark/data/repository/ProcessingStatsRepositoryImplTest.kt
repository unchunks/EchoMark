package com.unchunks.echomark.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.bookmark.model.ProcessingStage
import com.unchunks.echomark.domain.bookmark.model.StageDurationKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 段ごとの所要時間の平均の記録(DataStore)。 */
class ProcessingStatsRepositoryImplTest {

    // 実ファイルの DataStore は Windows 上の JVM で上書き rename に失敗することがあるため、メモリ上の実装を使う
    private val repository = ProcessingStatsRepositoryImpl(InMemoryStatsDataStore())

    private class InMemoryStatsDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(state.value).also { state.value = it } }
    }

    private val analyzeWeb = StageDurationKey(ProcessingStage.ANALYZE, ContentKind.WEB_PAGE)
    private val extractAudio = StageDurationKey(ProcessingStage.EXTRACT_CONTENT, ContentKind.AUDIO)

    @Test
    fun 最初の記録はそのまま_以降は最近の記録を重く見た平均にする() = runBlocking {
        repository.recordStageDuration(analyzeWeb, 10_000L)
        assertEquals(10_000L, repository.averageStageDurations.first()[analyzeWeb])

        repository.recordStageDuration(analyzeWeb, 20_000L)
        // 10 秒 × 0.7 + 20 秒 × 0.3
        assertEquals(13_000L, repository.averageStageDurations.first()[analyzeWeb])
    }

    @Test
    fun 段と中身の種類ごとに分けて持つ() = runBlocking {
        repository.recordStageDuration(analyzeWeb, 10_000L)
        repository.recordStageDuration(extractAudio, 300_000L)

        assertEquals(
            mapOf(analyzeWeb to 10_000L, extractAudio to 300_000L),
            repository.averageStageDurations.first()
        )
    }

    @Test
    fun キーの読み書き() {
        assertEquals("avg_ms:ANALYZE:WEB_PAGE", ProcessingStatsRepositoryImpl.keyName(analyzeWeb))
        assertEquals(extractAudio, ProcessingStatsRepositoryImpl.parseKey("avg_ms:EXTRACT_CONTENT:AUDIO"))
        // 読めないキー(新しい版で増えた段など)は無視する
        assertNull(ProcessingStatsRepositoryImpl.parseKey("avg_ms:UNKNOWN:AUDIO"))
        assertNull(ProcessingStatsRepositoryImpl.parseKey("other"))
    }
}
