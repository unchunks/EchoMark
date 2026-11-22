package com.unchunks.echomark.data.podcast

import com.unchunks.echomark.domain.podcast.EpisodeLine
import com.unchunks.echomark.domain.podcast.EpisodeSource
import com.unchunks.echomark.domain.podcast.EpisodeStatus
import com.unchunks.echomark.domain.podcast.PodcastEpisode
import com.unchunks.echomark.domain.podcast.PodcastFormat
import com.unchunks.echomark.domain.podcast.PodcastSpeaker
import com.unchunks.echomark.domain.podcast.ReadingEntry
import com.unchunks.echomark.domain.podcast.ScriptGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** エピソードのファイルへの保存と、JSON の変換。 */
class PodcastEpisodeStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val episode = PodcastEpisode(
        id = "a1-b2",
        title = "Kotlin の話",
        createdAt = 1_000,
        format = PodcastFormat.DUO,
        sources = listOf(EpisodeSource(3, "Kotlin 2.3", "Zenn", "https://zenn.dev/a"), EpisodeSource(4, "メモ", null, null)),
        status = EpisodeStatus.READY,
        lines = listOf(
            EpisodeLine(PodcastSpeaker.HOST, "こんにちは。", "こんにちは。", null, 0),
            EpisodeLine(PodcastSpeaker.GUEST, "GitHub の話。", "ギットハブの話。", 1, 1_200)
        ),
        readings = listOf(ReadingEntry("GitHub", "ギットハブ")),
        generator = ScriptGenerator.AI,
        audioFile = "episode.wav",
        durationMs = 9_000,
        sizeBytes = 12_345,
        failureReason = null
    )

    @Test
    fun JSONに書いて読み戻せる() {
        assertEquals(episode, PodcastEpisodeJson.decode(PodcastEpisodeJson.encode(episode)))
        val generating = PodcastEpisode("x", "t", 1, PodcastFormat.SOLO, emptyList(), EpisodeStatus.GENERATING)
        assertEquals(generating, PodcastEpisodeJson.decode(PodcastEpisodeJson.encode(generating)))
    }

    @Test
    fun 読めない値は除く() {
        assertNull(PodcastEpisodeJson.decode("壊れた"))
        assertNull(PodcastEpisodeJson.decode("""{"id":"a","format":"TRIO","status":"READY"}"""))
        val decoded = PodcastEpisodeJson.decode(
            """{"id":"a","title":"t","createdAt":1,"format":"SOLO","status":"READY",
               "sources":[{"bookmarkId":1,"title":"x","url":"javascript:alert(1)"},{"title":"id なし"}],
               "lines":[{"speaker":"ROBOT","text":"x"}],
               "readings":[{"surface":"","reading":"から"}]}"""
        )!!
        assertEquals(listOf(EpisodeSource(1, "x", null, null)), decoded.sources)
        assertTrue(decoded.lines.isEmpty())
        assertTrue(decoded.readings.isEmpty())
    }

    @Test
    fun 保存して一覧し_消せる() {
        val store = PodcastEpisodeStore(folder.root)
        store.write(episode)
        store.write(episode.copy(id = "newer", createdAt = 2_000))
        assertEquals(listOf("newer", "a1-b2"), store.list().map { it.id })
        assertEquals(episode, store.read("a1-b2"))

        File(store.dirOf("a1-b2"), "episode.wav").writeBytes(ByteArray(10))
        assertEquals(10L, store.audioFileOf(episode)?.length())

        assertTrue(store.delete("a1-b2"))
        assertNull(store.read("a1-b2"))
        assertFalse(store.dirOf("a1-b2").exists())
        store.deleteAll()
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun ほかの場所を指すidとファイル名は使わない() {
        val store = PodcastEpisodeStore(folder.newFolder("podcasts"))
        assertNull(store.read("../secret"))
        assertFalse(store.delete(".."))
        assertThrows(IllegalArgumentException::class.java) { store.write(episode.copy(id = "../x")) }
        store.write(episode.copy(audioFile = "../../datastore/x"))
        assertNull(store.audioFileOf(episode.copy(audioFile = "../../datastore/x")))
    }

    @Test
    fun 壊れたファイルは一覧に出さない() {
        val store = PodcastEpisodeStore(folder.root)
        File(folder.root, "broken").mkdirs()
        File(folder.root, "broken/episode.json").writeText("{")
        store.write(episode)
        assertEquals(listOf("a1-b2"), store.list().map { it.id })
    }
}
