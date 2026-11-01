package com.unchunks.echomark.data.extract

import com.unchunks.echomark.domain.extract.ExtractedContent
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** テキストファイル(text/plain)の中身を読む。 */
object TextFileReader {

    /** 読むバイト数の上限(UTF-8 の日本語で [ExtractedContent.MAX_TEXT_LENGTH] 字を少し超える量) */
    private const val MAX_BYTES = ExtractedContent.MAX_TEXT_LENGTH * 3 + 4

    private val SHIFT_JIS: Charset? = runCatching { Charset.forName("windows-31j") }.getOrNull()

    /** 先頭から読んで文字列にする。(本文, 途中までにしたか) */
    fun read(file: File): Pair<String, Boolean> {
        val size = file.length()
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(minOf(size, MAX_BYTES.toLong()).toInt())
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read)
        }
        return decode(bytes, truncatedInput = size > bytes.size) to (size > bytes.size)
    }

    /**
     * バイト列を文字列にする。UTF-8(BOM 付きも)として読めなければ Shift_JIS(Windows の日本語)として読む。
     * [truncatedInput] なら末尾で切れた文字は捨てる。
     */
    fun decode(bytes: ByteArray, truncatedInput: Boolean = false): String {
        val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }
        strictDecode(body, Charsets.UTF_8, truncatedInput)?.let { return it }
        SHIFT_JIS?.let { charset -> strictDecode(body, charset, truncatedInput)?.let { return it } }
        return String(body, Charsets.UTF_8)
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset, truncatedInput: Boolean): String? {
        // 途中で切った場合、最後の文字が欠けていることがあるため、末尾の数バイトを削って試す
        val trims = if (truncatedInput) 0..3 else 0..0
        for (trim in trims) {
            if (bytes.size < trim) break
            try {
                return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, bytes.size - trim))
                    .toString()
            } catch (_: CharacterCodingException) {
                // 次を試す
            }
        }
        return null
    }
}
