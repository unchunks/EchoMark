package com.unchunks.echomark.data.repository

import com.unchunks.echomark.domain.bookmark.model.ReadingTime

/**
 * 一覧に出す、読む時間の目安の覚え(ブックマークの ID → 本文の長さと分数)。
 * 一覧の問い合わせは本文を読まず長さだけを返すので、長さが覚えと違う(初めて・本文が変わった)ものだけ本文を読んで求め直す。
 * 一覧はブックマークが変わるたびに全件を読み直すが、そのたびに全件の本文を読まずに済む。
 */
internal class ReadingMinutesCache(private val maxEntries: Int = MAX_ENTRIES) {

    private class Entry(val length: Int, val minutes: Int?)

    // 古く使っていないものから捨てる(アクセス順)
    private val entries = object : LinkedHashMap<Long, Entry>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Entry>?): Boolean = size > maxEntries
    }

    /**
     * [lengths](ID → 本文の長さ。本文の無いものは入れない)の、読む時間の目安(分。短いものは null)。
     * 覚えに無い・長さが変わったものだけ [loadContents] で本文を読む(ID は [LOAD_CHUNK_SIZE] 件ずつに分けて渡す)。
     */
    suspend fun minutesFor(
        lengths: Map<Long, Int>,
        loadContents: suspend (ids: List<Long>) -> Map<Long, String?>
    ): Map<Long, Int?> {
        val result = HashMap<Long, Int?>(lengths.size)
        val missing = ArrayList<Long>()
        synchronized(entries) {
            lengths.forEach { (id, length) ->
                val entry = entries[id]
                if (entry != null && entry.length == length) result[id] = entry.minutes else missing += id
            }
        }
        missing.chunked(LOAD_CHUNK_SIZE).forEach { chunk ->
            val contents = loadContents(chunk)
            val computed = chunk.map { id -> id to ReadingTime.minutes(contents[id]) }
            synchronized(entries) {
                computed.forEach { (id, minutes) ->
                    entries[id] = Entry(lengths.getValue(id), minutes)
                    result[id] = minutes
                }
            }
        }
        return result
    }

    companion object {
        /** 覚えておく最大の件数(1件は数十バイト) */
        const val MAX_ENTRIES = 20_000

        /** 本文を読むときに1回で渡す ID の数(SQLite の変数の数の上限より十分少なく) */
        const val LOAD_CHUNK_SIZE = 500

        private const val INITIAL_CAPACITY = 256
        private const val LOAD_FACTOR = 0.75f
    }
}
