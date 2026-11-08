package com.unchunks.echomark.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class NotifiedHistoryCodecTest {

    @Test
    fun エンコードしてデコードすると元に戻る() {
        val original = mapOf(1L to 1_700_000_000_000L, 22L to 1_800_000_000_000L)
        assertEquals(original, decodeNotified(encodeNotified(original)))
    }

    @Test
    fun 壊れた要素は無視される() {
        val decoded = decodeNotified(setOf("1:100", "abc", "2:", ":3", "4:5:6", "x:y", "7:8"))
        assertEquals(mapOf(1L to 100L, 7L to 8L), decoded)
    }

    @Test
    fun 空集合は空マップ() {
        assertEquals(emptyMap<Long, Long>(), decodeNotified(emptySet()))
    }
}
