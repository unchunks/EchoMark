package com.unchunks.echomark.data.extract.transcribe

import com.unchunks.echomark.data.extract.media.PcmChunk
import com.unchunks.echomark.data.extract.media.PcmFormat
import com.unchunks.echomark.domain.provider.LlmException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TranscriberTest {

    private val file = File("unused.pcm")

    /** 5 分ごとの区間 */
    private fun chunks(count: Int): List<PcmChunk> {
        val size = PcmFormat.millisToBytes(5 * 60_000L)
        return (0 until count).map { PcmChunk(file, it * size, (it + 1) * size) }
    }

    private class Scripted(override val name: String, private val results: List<() -> String?>) : TranscriptionProvider {
        val calls = mutableListOf<Long>()
        override suspend fun transcribe(chunk: PcmChunk): String? {
            calls += chunk.startByte
            return results[calls.size - 1]()
        }
    }

    @Test
    fun 一区間だけなら時刻の見出しを付けない() = runBlocking {
        val provider = Scripted("cloud", listOf({ " こんにちは " }))

        val transcript = Transcriber.transcribe(chunks(1), listOf(provider))!!

        assertEquals("こんにちは", transcript.text)
        assertEquals(5 * 60_000L, transcript.transcribedMillis)
        assertFalse(transcript.incomplete)
        assertEquals(listOf("cloud"), transcript.engines)
    }

    @Test
    fun 複数の区間は開始時刻の見出しを付けてつなぎ_話していない区間は省く() = runBlocking {
        val provider = Scripted("cloud", listOf({ "一つ目" }, { "" }, { "三つ目" }))

        val transcript = Transcriber.transcribe(chunks(3), listOf(provider))!!

        assertEquals("[0:00]\n一つ目\n\n[10:00]\n三つ目", transcript.text)
        assertEquals(15 * 60_000L, transcript.transcribedMillis)
    }

    @Test
    fun クラウドが失敗したら端末内に切り替え_以降もクラウドは使わない() = runBlocking {
        val cloud = Scripted("cloud", listOf({ "クラウド" }, { throw LlmException.Network() }))
        val device = Scripted("on-device", listOf({ "端末2" }, { "端末3" }))

        val transcript = Transcriber.transcribe(chunks(3), listOf(cloud, device))!!

        assertEquals(2, cloud.calls.size)
        assertEquals(2, device.calls.size)
        assertEquals("[0:00]\nクラウド\n\n[5:00]\n端末2\n\n[10:00]\n端末3", transcript.text)
        assertEquals(listOf("cloud", "on-device"), transcript.engines)
        assertFalse(transcript.incomplete)
    }

    @Test
    fun 使える仕組みが無くなったらそこまでの結果を返す() = runBlocking {
        val device = Scripted("on-device", listOf({ "最初" }, { null }))

        val transcript = Transcriber.transcribe(chunks(3), listOf(device))!!

        assertEquals("[0:00]\n最初", transcript.text)
        assertTrue(transcript.incomplete)
        assertEquals(5 * 60_000L, transcript.transcribedMillis)
    }

    @Test
    fun 何も起こせなければnull() = runBlocking {
        assertNull(Transcriber.transcribe(chunks(2), emptyList()))
        assertNull(Transcriber.transcribe(chunks(1), listOf(Scripted("x", listOf({ "  " })))))
    }

    @Test
    fun 時刻の表示() {
        assertEquals("0:00", Transcriber.formatTimestamp(999))
        assertEquals("5:00", Transcriber.formatTimestamp(300_000))
        assertEquals("1:05:09", Transcriber.formatTimestamp(3_909_000))
    }
}
