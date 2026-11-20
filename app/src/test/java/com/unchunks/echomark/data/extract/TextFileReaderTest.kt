package com.unchunks.echomark.data.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.Charset

class TextFileReaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun UTF8とBOM付きUTF8を読む() {
        assertEquals("日本語のメモ", TextFileReader.decode("日本語のメモ".toByteArray()))
        assertEquals("BOM", TextFileReader.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "BOM".toByteArray()))
    }

    @Test
    fun UTF8として読めなければShiftJISとして読む() {
        val sjis = "議事録 ①".toByteArray(Charset.forName("windows-31j"))

        assertEquals("議事録 ①", TextFileReader.decode(sjis))
    }

    @Test
    fun 途中で切れた文字は捨てる() {
        val bytes = "あいう".toByteArray()

        assertEquals("あい", TextFileReader.decode(bytes.copyOf(bytes.size - 1), truncatedInput = true))
    }

    @Test
    fun 大きなファイルは先頭だけ読む() {
        val big = temp.newFile("big.txt").apply { writeText("あ".repeat(60_000)) }
        val small = temp.newFile("small.txt").apply { writeText("短いメモ") }

        val (bigText, bigTruncated) = TextFileReader.read(big)
        val (smallText, smallTruncated) = TextFileReader.read(small)

        assertTrue(bigTruncated)
        assertTrue(bigText.length in 50_000..50_002)
        assertTrue(bigText.all { it == 'あ' })
        assertEquals("短いメモ", smallText)
        assertFalse(smallTruncated)
    }
}
