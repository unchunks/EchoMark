package com.unchunks.echomark.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.bookmark.model.ProcessingStage
import com.unchunks.echomark.domain.bookmark.model.StageDurationKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 段ごとの所要時間の平均の記録(DataStore)。 */
class ProcessingStatsRepositoryImplTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository by lazy {
        ProcessingStatsRepositoryImpl(
            PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "stats.preferences_pb") }
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
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
