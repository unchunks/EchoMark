package com.unchunks.echomark.data.repository

import com.unchunks.echomark.domain.bookmark.model.ReadingTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 一覧の読む時間の目安の覚え。本文を読むのは、初めてのものと本文の長さが変わったものだけ */
class ReadingMinutesCacheTest {

    private val long = "日本語の文章です。".repeat(200)
    private val longer = "日本語の文章です。".repeat(400)

    @Test
    fun 初めてのものだけ本文を読み_長さが変わったら読み直す() = runBlocking {
        val cache = ReadingMinutesCache()
        val contents = mutableMapOf<Long, String?>(1L to long, 2L to "短い")
        val loaded = mutableListOf<List<Long>>()
        val load: suspend (List<Long>) -> Map<Long, String?> = { ids -> loaded += ids; ids.associateWith { contents[it] } }

        val first = cache.minutesFor(mapOf(1L to long.length, 2L to 2), load)
        assertEquals(ReadingTime.minutes(long), first[1L])
        assertNull(first[2L])
        assertEquals(listOf(listOf(1L, 2L)), loaded)

        // 変わっていなければ読まない
        cache.minutesFor(mapOf(1L to long.length, 2L to 2), load)
        assertEquals(1, loaded.size)

        // 本文が変わった(長さが変わった)ものだけ読み直す
        contents[1L] = longer
        val changed = cache.minutesFor(mapOf(1L to longer.length, 2L to 2), load)
        assertEquals(listOf(1L), loaded.last())
        assertEquals(ReadingTime.minutes(longer), changed[1L])
    }

    @Test
    fun 本文は区切って読み_覚えきれない件数でも全部の目安を返す() = runBlocking {
        val cache = ReadingMinutesCache(maxEntries = 10)
        val count = ReadingMinutesCache.LOAD_CHUNK_SIZE + 5
        val chunks = mutableListOf<Int>()

        val minutes = cache.minutesFor((1L..count).associateWith { long.length }) { ids ->
            chunks += ids.size
            ids.associateWith { long }
        }

        assertEquals(listOf(ReadingMinutesCache.LOAD_CHUNK_SIZE, 5), chunks)
        assertEquals(count, minutes.size)
        assertEquals(setOf(ReadingTime.minutes(long)), minutes.values.toSet())
    }
}
