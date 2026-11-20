package com.unchunks.echomark.data.extract.media

import com.unchunks.components.speech.PcmChunk
import com.unchunks.components.speech.Transcriber
import com.unchunks.components.speech.TranscriptionProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** 文字起こしの進み具合は、文字起こしできた区間の数で数える。 */
class ProgressCountingProviderTest {

    private class ScriptedProvider(override val name: String, private val failFrom: Int) : TranscriptionProvider {
        var calls = 0
        override suspend fun transcribe(chunk: PcmChunk): String? = if (++calls >= failFrom) null else "text$calls"
    }

    @Test
    fun 次の仕組みに回した区間は一度だけ数える() = runBlocking {
        val chunks = (0 until 3).map { PcmChunk(File("unused"), it * 100L, (it + 1) * 100L) }
        // クラウドは 2 区間目から使えず、端末内に切り替わる
        val cloud = ScriptedProvider("cloud", failFrom = 2)
        val onDevice = ScriptedProvider("device", failFrom = Int.MAX_VALUE)
        var done = 0
        val progress = mutableListOf<Float>()
        val providers = listOf(cloud, onDevice).map { provider ->
            ProgressCountingProvider(provider) {
                done++
                progress += done.toFloat() / chunks.size
            }
        }

        Transcriber.transcribe(chunks, providers)

        assertEquals(listOf(1f / 3, 2f / 3, 1f), progress)
    }
}
