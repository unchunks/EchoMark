package com.unchunks.echomark.data.podcast.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 読み上げのエンジンが書いた WAV の読み方と、1つの WAV へのつなぎ方。 */
class WavAudioTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** 決めた形の WAV。[extraChunk] なら fmt と data の間に LIST を入れる */
    private fun wav(
        sampleRate: Int,
        channels: Int,
        bits: Int,
        format: Int,
        body: ByteArray,
        extraChunk: Boolean = false,
        dataSize: Int = body.size
    ): ByteArray {
        val list = if (extraChunk) ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put("LIST".toByteArray()).putInt(4).put("INFO".toByteArray()).array() else ByteArray(0)
        val buffer = ByteBuffer.allocate(12 + 24 + list.size + 8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(4 + 24 + list.size + 8 + body.size).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(format.toShort()).putShort(channels.toShort())
            .putInt(sampleRate).putInt(sampleRate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        buffer.put(list)
        buffer.put("data".toByteArray()).putInt(dataSize).put(body)
        return buffer.array()
    }

    private fun shorts(vararg values: Int): ByteArray =
        ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putShort(it.toShort()) } }.array()

    @Test
    fun モノラル16ビットを読み_ほかのチャンクは飛ばす() {
        val audio = WavAudio.read(wav(22_050, 1, 16, 1, shorts(1, -2, 300), extraChunk = true))
        assertEquals(22_050, audio.sampleRate)
        assertArrayEquals(shortArrayOf(1, -2, 300), audio.samples)
    }

    @Test
    fun ステレオはモノラルにまとめる() {
        val audio = WavAudio.read(wav(16_000, 2, 16, 1, shorts(100, 300, -100, -300)))
        assertArrayEquals(shortArrayOf(200, -200), audio.samples)
    }

    @Test
    fun 八ビットと浮動小数点も読む() {
        assertArrayEquals(shortArrayOf(0, (127 shl 8).toShort()), WavAudio.read(wav(8_000, 1, 8, 1, byteArrayOf(128.toByte(), 255.toByte()))).samples)
        val floats = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.5f).putFloat(-1f).array()
        assertArrayEquals(shortArrayOf(16_384, (-Short.MAX_VALUE).toShort()), WavAudio.read(wav(8_000, 1, 32, 3, floats)).samples)
    }

    @Test
    fun dataの長さが0なら残り全部を読む() {
        assertEquals(3, WavAudio.read(wav(8_000, 1, 16, 1, shorts(1, 2, 3), dataSize = 0)).samples.size)
    }

    @Test
    fun 読めないものは断る() {
        assertThrows(UnsupportedWavException::class.java) { WavAudio.read("not a wav".toByteArray()) }
        assertThrows(UnsupportedWavException::class.java) { WavAudio.read(wav(8_000, 1, 12, 1, ByteArray(6))) }
    }

    @Test
    fun 周波数を変える() {
        val up = WavAudio.resample(PcmAudio(8_000, shortArrayOf(0, 100)), 16_000)
        assertEquals(16_000, up.sampleRate)
        assertArrayEquals(shortArrayOf(0, 50, 100, 100), up.samples)
        val same = PcmAudio(8_000, shortArrayOf(1))
        assertArrayEquals(same.samples, WavAudio.resample(same, 8_000).samples)
    }

    @Test
    fun つないだWAVは最初の周波数にそろえ_行の位置と長さが分かる() {
        val file = folder.newFile("out.wav")
        val joiner = WavJoiner(file)
        joiner.appendSilence(500) // 音声が無いうちは何もしない
        assertEquals(0, joiner.positionMs)
        joiner.append(PcmAudio(1_000, ShortArray(1_000) { 10 }))
        joiner.appendSilence(500)
        assertEquals(1_500, joiner.positionMs)
        joiner.append(PcmAudio(2_000, ShortArray(1_000) { 20 })) // 0.5 秒(1,000 Hz にすると 500 サンプル)
        assertEquals(2_000, joiner.finish())

        val read = WavAudio.read(file)
        assertEquals(1_000, read.sampleRate)
        assertEquals(2_000, read.samples.size)
        assertEquals(10, read.samples[0].toInt())
        assertEquals(0, read.samples[1_200].toInt())
        assertEquals(20, read.samples[1_999].toInt())
        assertEquals(WavAudio.HEADER_BYTES + 4_000L, file.length())
    }

    @Test
    fun 音声を1つも足さなければ断る() {
        val joiner = WavJoiner(folder.newFile("empty.wav"))
        assertThrows(UnsupportedWavException::class.java) { joiner.finish() }
        joiner.close()
    }
}
