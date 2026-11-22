package com.unchunks.echomark.data.podcast.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** モノラル・16 ビットの音声。 */
class PcmAudio(val sampleRate: Int, val samples: ShortArray) {
    val durationMs: Long get() = samples.size * 1_000L / sampleRate
}

/** 読めない WAV(形式が違う・壊れている)。 */
class UnsupportedWavException(message: String) : Exception(message)

/**
 * 読み上げのエンジンが書いた WAV を読み、1つの WAV につなげる(純粋な JVM のコード。単体テスト可能)。
 * エンジンや声によって、サンプリング周波数・チャンネル数・ビット数が違うことがあるため、読むときにモノラル・16 ビットにそろえ、
 * つなげるときに最初の周波数へ変換する。
 */
object WavAudio {
    private const val FORMAT_PCM = 1
    private const val FORMAT_FLOAT = 3
    private const val FORMAT_EXTENSIBLE = 0xFFFE
    const val HEADER_BYTES = 44

    /** WAV のバイト列を読む。PCM 8・16・24・32 ビットと 32 ビット浮動小数点に対応する */
    fun read(bytes: ByteArray): PcmAudio {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 12 || tag(bytes, 0) != "RIFF" || tag(bytes, 8) != "WAVE") throw UnsupportedWavException("WAV ではありません")
        var offset = 12
        var format = -1
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var data: Pair<Int, Int>? = null
        while (offset + 8 <= bytes.size) {
            val id = tag(bytes, offset)
            val size = buffer.getInt(offset + 4).toLong().coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            val body = offset + 8
            when (id) {
                "fmt " -> {
                    if (size < 16 || body + 16 > bytes.size) throw UnsupportedWavException("fmt が短すぎます")
                    format = buffer.getShort(body).toInt() and 0xFFFF
                    channels = buffer.getShort(body + 2).toInt()
                    sampleRate = buffer.getInt(body + 4)
                    bits = buffer.getShort(body + 14).toInt()
                    if (format == FORMAT_EXTENSIBLE && size >= 26 && body + 26 <= bytes.size) {
                        format = buffer.getShort(body + 24).toInt() and 0xFFFF
                    }
                }
                // エンジンが長さを決める前に書き終えた WAV では、data の長さが 0 や大きすぎる値のことがある。そのときは残り全部
                "data" -> data = body to (if (size == 0 || body + size > bytes.size) bytes.size - body else size)
            }
            if (data != null && format >= 0) break
            offset = body + size + (size and 1)
        }
        val (start, length) = data ?: throw UnsupportedWavException("data がありません")
        if (format < 0) throw UnsupportedWavException("fmt がありません")
        if (channels !in 1..8 || sampleRate !in 1_000..384_000) throw UnsupportedWavException("チャンネル数か周波数が読めません")
        val bytesPerSample = when {
            format == FORMAT_PCM && bits in setOf(8, 16, 24, 32) -> bits / 8
            format == FORMAT_FLOAT && bits == 32 -> 4
            else -> throw UnsupportedWavException("対応していない形式です(形式 $format・$bits ビット)")
        }
        val frameBytes = bytesPerSample * channels
        val frames = length / frameBytes
        val samples = ShortArray(frames)
        for (frame in 0 until frames) {
            var sum = 0
            for (channel in 0 until channels) {
                val at = start + frame * frameBytes + channel * bytesPerSample
                sum += sampleAt(buffer, bytes, at, format, bits)
            }
            samples[frame] = (sum / channels).toShort()
        }
        return PcmAudio(sampleRate, samples)
    }

    fun read(file: File): PcmAudio = read(file.readBytes())

    private fun sampleAt(buffer: ByteBuffer, bytes: ByteArray, at: Int, format: Int, bits: Int): Int = when {
        format == FORMAT_FLOAT -> (buffer.getFloat(at).coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt()
        bits == 8 -> ((bytes[at].toInt() and 0xFF) - 128) shl 8
        bits == 16 -> buffer.getShort(at).toInt()
        bits == 24 -> (bytes[at + 2].toInt() shl 8) or (bytes[at + 1].toInt() and 0xFF)
        else -> buffer.getInt(at) shr 16
    }

    /** 周波数を [targetRate] に変える(線形補間)。同じならそのまま */
    fun resample(audio: PcmAudio, targetRate: Int): PcmAudio {
        if (audio.sampleRate == targetRate || audio.samples.isEmpty()) return PcmAudio(targetRate, audio.samples)
        val outSize = (audio.samples.size.toLong() * targetRate / audio.sampleRate).toInt().coerceAtLeast(1)
        val ratio = audio.sampleRate.toDouble() / targetRate
        val last = audio.samples.lastIndex
        val out = ShortArray(outSize) { i ->
            val position = i * ratio
            val index = position.toInt().coerceAtMost(last)
            val next = (index + 1).coerceAtMost(last)
            val fraction = position - index
            (audio.samples[index] * (1 - fraction) + audio.samples[next] * fraction).roundToInt().toShort()
        }
        return PcmAudio(targetRate, out)
    }

    /** 44 バイトの WAV の頭(PCM・モノラル・16 ビット) */
    fun header(sampleRate: Int, dataBytes: Long): ByteArray {
        val data = dataBytes.coerceAtMost(UInt.MAX_VALUE.toLong() - 36).toInt()
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(FORMAT_PCM.toShort()); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data)
        }.array()
    }

    private fun tag(bytes: ByteArray, at: Int): String = String(bytes, at, 4, Charsets.US_ASCII)
}

/**
 * 行ごとの音声を1つの WAV に書いていく。周波数は最初に足した音声のものにし、違う周波数は変換する。
 * 行の始まりの位置([positionMs])を覚えておき、画面で台本と音声を合わせるのに使う。
 */
class WavJoiner(private val file: File) : AutoCloseable {
    private val output = RandomAccessFile(file, "rw")
    private var sampleRate: Int? = null
    private var samplesWritten = 0L

    init {
        output.setLength(0)
        output.write(ByteArray(WavAudio.HEADER_BYTES))
    }

    /** ここまでの長さ(ミリ秒) */
    val positionMs: Long get() = sampleRate?.let { samplesWritten * 1_000L / it } ?: 0L

    fun append(audio: PcmAudio) {
        val rate = sampleRate ?: audio.sampleRate.also { sampleRate = it }
        writeSamples(WavAudio.resample(audio, rate).samples)
    }

    /** 無音を足す(行と行の間)。まだ音声が無ければ何もしない */
    fun appendSilence(ms: Long) {
        val rate = sampleRate ?: return
        writeSamples(ShortArray((rate * ms / 1_000).toInt()))
    }

    private fun writeSamples(samples: ShortArray) {
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putShort(it) }
        output.write(bytes.array())
        samplesWritten += samples.size
    }

    /** 頭に長さを書いて閉じる。音声を1つも足していなければ [UnsupportedWavException] */
    fun finish(): Long {
        val rate = sampleRate ?: throw UnsupportedWavException("音声がありません")
        output.seek(0)
        output.write(WavAudio.header(rate, samplesWritten * 2))
        output.close()
        return samplesWritten * 1_000L / rate
    }

    override fun close() {
        runCatching { output.close() }
    }
}
