package com.unchunks.echomark.data.extract.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun ステレオはチャンネルの平均でモノラルにする() {
        val stereo = shortArrayOf(100, 300, -200, 0, 32767, 32767)

        assertArrayEquals(shortArrayOf(200, -100, 32767), PcmMath.downmixToMono(stereo, channels = 2))
        assertArrayEquals(shortArrayOf(1, 2), PcmMath.downmixToMono(shortArrayOf(1, 2, 3), length = 2, channels = 1))
    }

    @Test
    fun floatは16bitにして範囲外は丸める() {
        val pcm = PcmMath.floatToPcm16(floatArrayOf(0f, 1f, -1f, 2f, 0.5f))

        assertArrayEquals(shortArrayOf(0, 32767, -32767, 32767, 16384), pcm)
    }

    @Test
    fun リトルエンディアンのバイト列にする() {
        assertArrayEquals(
            byteArrayOf(0x34, 0x12, 0xFF.toByte(), 0xFF.toByte()),
            PcmMath.toLittleEndianBytes(shortArrayOf(0x1234, -1))
        )
    }

    @Test
    fun 同じ周波数ならそのまま() {
        val resampler = LinearResampler(16_000, 16_000)

        assertArrayEquals(shortArrayOf(1, 2, 3), resampler.process(shortArrayOf(1, 2, 3)))
    }

    @Test
    fun 周波数を3分の1にすると3つおきのサンプルになる() {
        val input = ShortArray(30) { (it * 10).toShort() }

        val out = LinearResampler(48_000, 16_000).process(input)

        assertArrayEquals(ShortArray(10) { (it * 30).toShort() }, out)
    }

    @Test
    fun 分けて渡しても一度に渡したときと同じ結果() {
        val input = ShortArray(1000) { ((it * 37) % 2000 - 1000).toShort() }
        val whole = LinearResampler(44_100, 16_000).process(input)

        val resampler = LinearResampler(44_100, 16_000)
        val pieces = listOf(0..120, 121..121, 122..640, 641..999)
            .map { range -> resampler.process(input.copyOfRange(range.first, range.last + 1)) }
        val joined = pieces.fold(ShortArray(0)) { acc, part -> acc + part }

        // 位置の計算の丸め誤差で 1 ずれることはある
        assertEquals(whole.size, joined.size)
        whole.indices.forEach { i -> assertTrue("i=$i", kotlin.math.abs(whole[i] - joined[i]) <= 1) }
        // 長さは入力 × 周波数の比
        assertTrue(whole.size in 362..363)
    }

    @Test
    fun アップサンプリングは間を補間する() {
        val out = LinearResampler(8_000, 16_000).process(shortArrayOf(0, 100, 200))

        assertArrayEquals(shortArrayOf(0, 50, 100, 150), out)
    }

    @Test
    fun WAVのヘッダー() {
        val header = ByteBuffer.wrap(WavHeader.create(dataBytes = 32_000)).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(44, header.capacity())
        assertEquals("RIFF", String(header.array(), 0, 4, Charsets.US_ASCII))
        assertEquals(36 + 32_000, header.getInt(4))
        assertEquals("WAVE", String(header.array(), 8, 4, Charsets.US_ASCII))
        assertEquals(1.toShort(), header.getShort(20)) // PCM
        assertEquals(1.toShort(), header.getShort(22)) // モノラル
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28)) // バイト/秒
        assertEquals(16.toShort(), header.getShort(34))
        assertEquals("data", String(header.array(), 36, 4, Charsets.US_ASCII))
        assertEquals(32_000, header.getInt(40))
    }

    @Test
    fun いちばん静かな区間を選ぶ() {
        val samples = ShortArray(400) { i -> if (i in 200 until 300) 5 else 5000 }

        assertEquals(200, PcmMath.quietestFrameStart(samples, frameSize = 100))
        assertEquals(0, PcmMath.quietestFrameStart(ShortArray(50), frameSize = 100))
    }

    @Test
    fun 区切りの位置は探索範囲の静かなところになり_短い最後は前に含める() {
        val chunkMillis = 60_000L
        val chunkBytes = PcmFormat.millisToBytes(chunkMillis)
        val total = chunkBytes * 2 + PcmFormat.millisToBytes(3_000) // 最後の 3 秒は短いので前に含める
        val windows = mutableListOf<Pair<Long, Long>>()

        val bounds = PcmChunker.boundaries(total, chunkMillis) { start, size ->
            windows += start to size
            1001 // 奇数でもサンプルの境目(偶数)にそろえる
        }

        val search = PcmFormat.millisToBytes(4_000)
        assertEquals(listOf(chunkBytes - search, chunkBytes - search + 1000 + chunkBytes - search), windows.map { it.first })
        assertEquals(listOf(0L, chunkBytes - search + 1000, chunkBytes - search + 1000 + chunkBytes - search + 1000, total), bounds)
    }

    @Test
    fun 短い音声は区切らない() {
        val total = PcmFormat.millisToBytes(30_000)

        assertEquals(listOf(0L, total), PcmChunker.boundaries(total, 60_000L) { _, _ -> 0 })
    }

    @Test
    fun ファイルを区切ると全体を隙間なく覆い_WAVとして読める() {
        val samples = ShortArray(PcmFormat.SAMPLE_RATE * 10) { i -> ((i % 50) * 400 - 10_000).toShort() }
        val file = temp.newFile("audio.pcm").apply { writeBytes(PcmMath.toLittleEndianBytes(samples)) }

        val chunks = PcmChunker.split(file, file.length(), chunkMillis = 4_000L)

        assertEquals(2, chunks.size)
        assertEquals(0L, chunks.first().startByte)
        assertEquals(file.length(), chunks.last().endByte)
        assertEquals(chunks[0].endByte, chunks[1].startByte)
        assertEquals(0L, chunks[0].endByte % 2)
        val wav = chunks[1].toWavBytes()
        assertEquals(44 + chunks[1].sizeBytes.toInt(), wav.size)
        assertArrayEquals(file.readBytes().copyOfRange(chunks[1].startByte.toInt(), file.length().toInt()), wav.copyOfRange(44, wav.size))
    }

    @Test
    fun 区切りは無音の位置を選ぶ() {
        // 20 秒の音声。8.5 秒あたりだけ無音 → 10 秒で区切りたいとき、6〜10 秒の範囲で無音の位置を選ぶ
        val samples = ShortArray(PcmFormat.SAMPLE_RATE * 20) { i ->
            val second = i.toDouble() / PcmFormat.SAMPLE_RATE
            if (second in 8.5..8.7) 0 else ((i % 50) * 400 - 10_000).toShort()
        }
        val file = temp.newFile("audio2.pcm").apply { writeBytes(PcmMath.toLittleEndianBytes(samples)) }

        val chunks = PcmChunker.split(file, file.length(), chunkMillis = 10_000L)

        assertEquals(2, chunks.size)
        val splitSecond = chunks[0].durationMillis / 1000.0
        assertTrue("split=$splitSecond", splitSecond in 8.5..8.7)
    }
}
