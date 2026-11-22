package com.unchunks.echomark.data.podcast

import com.unchunks.echomark.data.podcast.audio.WavAudio
import com.unchunks.echomark.data.podcast.tts.PodcastSpeechEngine
import com.unchunks.echomark.data.podcast.tts.PodcastSpeechSession
import com.unchunks.echomark.domain.podcast.PodcastGenerationException
import com.unchunks.echomark.domain.podcast.PodcastSpeaker
import com.unchunks.echomark.domain.podcast.SpeechText
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 読み上げのエンジンの Fake。渡された文の 1 文字を 10 ミリ秒の音(1,000 Hz・16 ビット・モノラル)として WAV に書く。
 * 渡された文と話す人を覚えておく。
 */
class FakeSpeechEngine(
    private val maxChars: Int = 1_000,
    var openFailure: PodcastGenerationException? = null,
    var failAt: Int? = null
) : PodcastSpeechEngine {
    val spoken = mutableListOf<Pair<PodcastSpeaker, String>>()
    var closed = 0

    override suspend fun open(): PodcastSpeechSession {
        openFailure?.let { throw it }
        return object : PodcastSpeechSession {
            override val maxInputChars = maxChars
            override val hasDistinctVoices = false

            override suspend fun synthesize(text: SpeechText, speaker: PodcastSpeaker, output: File) {
                if (failAt == spoken.size) throw PodcastGenerationException("読み上げで音声を作れませんでした。")
                spoken += speaker to text.plain
                val samples = text.plain.length * 10
                val body = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
                repeat(samples) { body.putShort(1_000) }
                output.writeBytes(WavAudio.header(1_000, samples * 2L) + body.array())
            }

            override fun close() {
                closed++
            }
        }
    }
}

/** 積んだ・止めた ID を覚えておく */
class FakePodcastWorkScheduler : PodcastWorkScheduler {
    val enqueued = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    override fun enqueue(episodeId: String) {
        enqueued += episodeId
    }

    override fun cancel(episodeId: String) {
        cancelled += episodeId
    }
}
