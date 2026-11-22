package com.unchunks.echomark.data.podcast

import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.data.ai.LocalLlmProviders
import com.unchunks.echomark.data.podcast.audio.WavAudio
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.podcast.EpisodeStatus
import com.unchunks.echomark.domain.podcast.PodcastFormat
import com.unchunks.echomark.domain.podcast.PodcastGenerationException
import com.unchunks.echomark.domain.podcast.PodcastProgress
import com.unchunks.echomark.domain.podcast.PodcastSpeaker
import com.unchunks.echomark.domain.podcast.ReadingEntry
import com.unchunks.echomark.domain.podcast.ScriptGenerator
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.FakeApiKeyRepository
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.FakeLlmProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * エピソードを作る流れ(台本 → 読みの整え → 音声 → 1つの WAV)と、作り始める・消す。
 * AI は使えない(要約の読み上げ)にし、読み上げは [FakeSpeechEngine]。
 */
class PodcastGenerationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val bookmarks = FakeBookmarkRepository().apply {
        bookmarks.value = listOf(
            Bookmark(id = 1, type = BookmarkType.URL, contentUri = "https://zenn.dev/a", siteName = "Zenn", title = "GitHub の使い方",
                summary = "GitHub で 2026/11/20 に発表。詳しくは https://example.com を見る。", createdAt = 0, lastAccessedAt = 0),
            Bookmark(id = 2, type = BookmarkType.TEXT, title = "メモ", summary = "日本橋で会う。", createdAt = 0, lastAccessedAt = 0)
        )
    }
    private val settings = FakeAppSettingsRepository(LlmBackend.LOCAL)
    private val engine = FakeSpeechEngine()
    private val library by lazy { PodcastEpisodeLibrary(PodcastEpisodeStore(folder.newFolder("podcasts")), Dispatchers.Unconfined) }
    private val scheduler = FakePodcastWorkScheduler()

    private fun writer() = PodcastScriptWriter(
        LlmProviderResolver(LocalLlmProviders { throw ModelNotAvailableException() }, { FakeLlmProvider() }, settings, FakeApiKeyRepository())
    )

    private fun repository() = PodcastRepositoryImpl(library, scheduler, bookmarks).apply {
        clock = { 5_000 }
        var n = 0
        newId = { "ep${++n}" }
    }

    private fun runner() = PodcastEpisodeRunner(library, PodcastGenerator(writer(), engine), bookmarks, settings)

    @Test
    fun 作り始めると作成中で残り_裏の処理に積む() = runBlocking {
        val id = repository().create(listOf(2, 1, 2, 99), PodcastFormat.SOLO)

        val episode = library.get(id)!!
        assertEquals(EpisodeStatus.GENERATING, episode.status)
        assertEquals(listOf(2L, 1L), episode.sources.map { it.bookmarkId })
        assertEquals("メモ ほか1件", episode.title)
        assertEquals(5_000, episode.createdAt)
        assertEquals(listOf(id), scheduler.enqueued)
    }

    @Test
    fun 作り切ると音声と台本と読みを残す() = runBlocking {
        settings.readingDictionaryFlow.value = listOf(ReadingEntry("日本橋", "にほんばし"))
        val id = repository().create(listOf(1, 2), PodcastFormat.SOLO)
        val progress = mutableListOf<String>()

        val made = runner().run(id) { progress += it }!!

        assertEquals(EpisodeStatus.READY, made.status)
        assertEquals(ScriptGenerator.SUMMARY_READ, made.generator)
        assertEquals(made, library.get(id))
        // 読み上げに渡したのは整えた文(URL を読まない・日付・略語・辞書)。画面の台本はそのまま
        val line = made.lines.first { it.text.startsWith("GitHub で") }
        assertEquals("ギットハブで2026年11月20日に発表。", line.spoken)
        assertTrue(made.lines.any { it.text == "詳しくは https://example.com を見る。" && it.spoken == "詳しくはを見る。" })
        assertTrue(engine.spoken.any { it.second == "にほんばしで会う。" })
        // 使った読みは、利用者の辞書のものだけ(アプリに入れてある読みは出さない)
        assertEquals(listOf(ReadingEntry("日本橋", "にほんばし")), made.readings)
        // 行の始まりの位置は増えていき、音声の長さに収まる
        val starts = made.lines.mapNotNull { it.startMs }
        assertEquals(made.lines.size, starts.size)
        assertEquals(0L, starts.first())
        assertEquals(starts.sorted(), starts)
        assertTrue(starts.last() < made.durationMs!!)
        val audio = library.audioFileOf(made)!!
        assertEquals(made.sizeBytes, audio.length())
        assertEquals(made.durationMs, WavAudio.read(audio).durationMs)
        // 作業のファイルは消す。進み具合も消す
        assertFalse(File(library.dirOf(id), "work").exists())
        assertTrue(library.progress.value.isEmpty())
        assertEquals(PodcastProgress.Preparing.label, progress.first())
        assertEquals(1, engine.closed)
    }

    @Test
    fun 二人の掛け合いでは話す人ごとに声を分ける() = runBlocking {
        val id = repository().create(listOf(1), PodcastFormat.DUO)
        runner().run(id)
        assertEquals(setOf(PodcastSpeaker.HOST, PodcastSpeaker.GUEST), engine.spoken.map { it.first }.toSet())
    }

    @Test
    fun 読み上げを使えなければ理由を付けて失敗にする() = runBlocking {
        engine.openFailure = PodcastGenerationException("日本語の読み上げの声がありません。")
        val id = repository().create(listOf(1), PodcastFormat.SOLO)

        val made = runner().run(id)!!

        assertEquals(EpisodeStatus.FAILED, made.status)
        assertEquals("日本語の読み上げの声がありません。", made.failureReason)
        assertNull(made.audioFile)
        assertFalse(File(library.dirOf(id), PodcastGenerator.AUDIO_FILE).exists())
    }

    @Test
    fun ブックマークが消えていたら失敗にする() = runBlocking {
        val id = repository().create(listOf(1), PodcastFormat.SOLO)
        bookmarks.bookmarks.value = emptyList()
        assertEquals(EpisodeStatus.FAILED, runner().run(id)?.status)
    }

    @Test
    fun 消したエピソードは作らず_作り終えたものは作り直さない() = runBlocking {
        val repository = repository()
        val id = repository.create(listOf(1), PodcastFormat.SOLO)
        repository.delete(id)
        assertEquals(listOf(id), scheduler.cancelled)
        assertNull(runner().run(id))
        assertTrue(engine.spoken.isEmpty())

        val other = repository.create(listOf(1), PodcastFormat.SOLO)
        assertNotNull(runner().run(other))
        assertNull(runner().run(other))
    }

    @Test
    fun 古いものだけを消す_作っている途中のものは残す() = runBlocking {
        val repository = repository()
        val old = repository.create(listOf(1), PodcastFormat.SOLO)
        runner().run(old)
        val generating = repository.create(listOf(2), PodcastFormat.SOLO)
        repository.clock = { 9_000 }
        val recent = repository.create(listOf(2), PodcastFormat.SOLO)

        assertEquals(1, repository.deleteOlderThan(8_000))
        assertEquals(setOf(generating, recent), repository.episodes.first().map { it.id }.toSet())

        repository.deleteAll()
        assertTrue(repository.episodes.first().isEmpty())
        // 作っている途中のものは止めてから消す
        assertEquals(setOf(generating, recent), scheduler.cancelled.toSet())
    }

    @Test
    fun 選んだものが無ければ作り始めない() = runBlocking {
        val failure = runCatching { repository().create(listOf(99), PodcastFormat.SOLO) }.exceptionOrNull()
        assertEquals("選んだブックマークが見つかりません。", failure?.message)
        assertTrue(scheduler.enqueued.isEmpty())
    }
}
