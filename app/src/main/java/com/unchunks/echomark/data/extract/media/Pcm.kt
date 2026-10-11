package com.unchunks.echomark.data.extract.media

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 文字起こしに渡す音声の形式: 16kHz・モノラル・16bit 符号付き PCM(リトルエンディアン)。
 * Android の音声認識(SpeechRecognizer に音声ファイルを渡すときの既定値)と同じで、WAV にすればクラウドにもそのまま送れる。
 */
object PcmFormat {
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
    const val BYTES_PER_SECOND = SAMPLE_RATE * BYTES_PER_SAMPLE

    fun bytesToMillis(bytes: Long): Long = bytes * 1000 / BYTES_PER_SECOND

    /** ミリ秒をバイト数に(サンプルの途中で切れないよう偶数にそろえる)。 */
    fun millisToBytes(millis: Long): Long = (millis * BYTES_PER_SECOND / 1000) and 1L.inv()
}

/** PCM の変換(デコーダーの出力 → 16kHz モノラル)の計算。 */
object PcmMath {

    /** インターリーブされた多チャンネルの 16bit PCM を、チャンネルの平均でモノラルにする。 */
    fun downmixToMono(interleaved: ShortArray, length: Int = interleaved.size, channels: Int): ShortArray {
        require(channels >= 1) { "channels=$channels" }
        if (channels == 1) return interleaved.copyOf(length)
        val frames = length / channels
        val out = ShortArray(frames)
        for (frame in 0 until frames) {
            var sum = 0
            val base = frame * channels
            for (c in 0 until channels) sum += interleaved[base + c]
            out[frame] = (sum / channels).toShort()
        }
        return out
    }

    /** -1.0〜1.0 の float PCM を 16bit にする(範囲外は丸める)。 */
    fun floatToPcm16(samples: FloatArray, length: Int = samples.size): ShortArray =
        ShortArray(length) { i ->
            (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
        }

    /** 16bit PCM をリトルエンディアンのバイト列にする(ファイルに書く形式)。 */
    fun toLittleEndianBytes(samples: ShortArray, length: Int = samples.size): ByteArray {
        val bytes = ByteArray(length * 2)
        for (i in 0 until length) {
            val v = samples[i].toInt()
            bytes[i * 2] = v.toByte()
            bytes[i * 2 + 1] = (v shr 8).toByte()
        }
        return bytes
    }

    /** [from] から [to] (含まない)までの二乗平均平方根(音の大きさ)。 */
    fun rms(samples: ShortArray, from: Int = 0, to: Int = samples.size): Double {
        if (to <= from) return 0.0
        var sum = 0.0
        for (i in from until to) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / (to - from))
    }

    /**
     * [samples] を [frameSize] サンプルずつに区切り、いちばん静かな区間の先頭位置を返す。
     * 長い音声を区切るとき、話の途中で切らないよう無音に近いところを探すのに使う。同じ大きさなら後ろを選ぶ。
     */
    fun quietestFrameStart(samples: ShortArray, frameSize: Int): Int {
        require(frameSize > 0)
        if (samples.size <= frameSize) return 0
        var bestStart = 0
        var bestRms = Double.MAX_VALUE
        var start = 0
        while (start + frameSize <= samples.size) {
            val value = rms(samples, start, start + frameSize)
            if (value <= bestRms) {
                bestRms = value
                bestStart = start
            }
            start += frameSize
        }
        return bestStart
    }
}

/**
 * 線形補間でサンプリング周波数を変える(モノラル 16bit)。デコーダーの出力を少しずつ渡せるよう、前回の続きの位置を覚えている。
 * 音声認識向け(音質より速さ)。ローパスフィルタはかけない。
 */
class LinearResampler(private val inputRate: Int, private val outputRate: Int) {
    init {
        require(inputRate > 0 && outputRate > 0)
    }

    private val step = inputRate.toDouble() / outputRate

    /** 次に出力するサンプルの位置(前回の最後のサンプルを 0 とした入力上の位置) */
    private var position = 0.0
    private var previous: Short? = null

    fun process(input: ShortArray, length: Int = input.size): ShortArray {
        if (length == 0) return ShortArray(0)
        if (inputRate == outputRate) return input.copyOf(length)
        val prev = previous
        val offset = if (prev != null) 1 else 0
        val count = length + offset
        fun at(index: Int): Int = if (prev != null && index == 0) prev.toInt() else input[index - offset].toInt()

        val out = ShortArray(((count - position) / step).toInt() + 2)
        var written = 0
        while (position < count - 1) {
            val index = floor(position).toInt()
            val fraction = position - index
            val value = at(index) * (1 - fraction) + at(index + 1) * fraction
            out[written++] = value.roundToInt().toShort()
            position += step
        }
        previous = at(count - 1).toShort()
        position -= (count - 1)
        return out.copyOf(written)
    }
}

/** WAV(RIFF)形式のヘッダー。 */
object WavHeader {
    const val SIZE = 44

    fun create(
        dataBytes: Long,
        sampleRate: Int = PcmFormat.SAMPLE_RATE,
        channels: Int = PcmFormat.CHANNELS,
        bitsPerSample: Int = PcmFormat.BITS_PER_SAMPLE
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        return ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((36 + dataBytes).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // fmt チャンクの大きさ
            putShort(1) // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * bitsPerSample / 8).toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes.toInt())
        }.array()
    }
}

/** PCM ファイルのうち、文字起こしに1回で渡す区間(バイト位置)。 */
data class PcmChunk(val file: File, val startByte: Long, val endByte: Long) {
    val sizeBytes: Long get() = endByte - startByte
    val startMillis: Long get() = PcmFormat.bytesToMillis(startByte)
    val durationMillis: Long get() = PcmFormat.bytesToMillis(sizeBytes)

    /** この区間の PCM を読む(区間は文字起こし1回分なので、メモリに載せてよい大きさ)。 */
    fun readBytes(): ByteArray = RandomAccessFile(file, "r").use { raf ->
        raf.seek(startByte)
        ByteArray(sizeBytes.toInt()).also { raf.readFully(it) }
    }

    /** WAV ファイルとしてのバイト列。 */
    fun toWavBytes(): ByteArray = WavHeader.create(sizeBytes) + readBytes()
}

/** 長い音声を区切る。 */
object PcmChunker {

    /** 区切り目を探す範囲(区切りたい位置より前の何ミリ秒の中で、いちばん静かなところで区切る) */
    private const val SEARCH_WINDOW_MILLIS = 4_000L

    /** 静かさを測る単位 */
    private const val FRAME_MILLIS = 50L

    /** 短すぎる最後の区間は前に含める */
    private const val MIN_LAST_CHUNK_MILLIS = 5_000L

    /**
     * [totalBytes] の PCM を、だいたい [chunkMillis] ごとの区間に分けたときの区切りの位置(バイト)。
     * [quietOffset] は「区切りたい位置の前の探索範囲」の中で、いちばん静かな位置(探索範囲の先頭からのバイト数)を返す。
     * 最後の区間が短すぎるときは、その前の区間に含める。
     */
    fun boundaries(totalBytes: Long, chunkMillis: Long, quietOffset: (windowStart: Long, windowBytes: Long) -> Long): List<Long> {
        val chunkBytes = PcmFormat.millisToBytes(chunkMillis)
        require(chunkBytes > 0)
        val searchBytes = PcmFormat.millisToBytes(SEARCH_WINDOW_MILLIS).coerceAtMost(chunkBytes / 2)
        val minLast = PcmFormat.millisToBytes(MIN_LAST_CHUNK_MILLIS)
        val result = mutableListOf(0L)
        var start = 0L
        while (totalBytes - start > chunkBytes + minLast) {
            val nominal = start + chunkBytes
            val windowStart = nominal - searchBytes
            val offset = quietOffset(windowStart, searchBytes).coerceIn(0L, searchBytes) and 1L.inv()
            val boundary = windowStart + offset
            result += boundary
            start = boundary
        }
        result += totalBytes
        return result
    }

    /** [file] の PCM を区切った区間。区切りは静かなところを探す。 */
    fun split(file: File, totalBytes: Long, chunkMillis: Long): List<PcmChunk> {
        val frameSamples = (PcmFormat.SAMPLE_RATE * FRAME_MILLIS / 1000).toInt()
        val bounds = RandomAccessFile(file, "r").use { raf ->
            boundaries(totalBytes, chunkMillis) { windowStart, windowBytes ->
                val bytes = ByteArray(windowBytes.toInt())
                raf.seek(windowStart)
                raf.readFully(bytes)
                val samples = ShortArray(bytes.size / 2)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                PcmMath.quietestFrameStart(samples, frameSamples).toLong() * PcmFormat.BYTES_PER_SAMPLE
            }
        }
        return bounds.zipWithNext { start, end -> PcmChunk(file, start, end) }.filter { it.sizeBytes > 0 }
    }
}
