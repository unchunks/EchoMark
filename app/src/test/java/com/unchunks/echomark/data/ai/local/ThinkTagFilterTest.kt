package com.unchunks.echomark.data.ai.local

import org.junit.Assert.assertEquals
import org.junit.Test

/** 推論モデルが回答の前に書く「<think>…</think>」を取り除く。 */
class ThinkTagFilterTest {

    private fun run(vararg chunks: String): String {
        val filter = ThinkTagFilter()
        return chunks.joinToString("") { filter.accept(it) } + filter.flush()
    }

    @Test
    fun 考えの部分を除いて回答だけを流す() {
        assertEquals("こんにちは。", run("<think>\n挨拶を返す\n</think>\n\nこんにちは。"))
    }

    @Test
    fun タグが増分の途中で分かれて届いても除く() {
        assertEquals("回答です", run("<th", "ink>考え</th", "ink>", "回答です"))
    }

    @Test
    fun タグの無い出力はそのまま流す() {
        assertEquals("a < b のとき", run("a <", " b のとき"))
    }

    @Test
    fun 考えの途中で終わったら考えは捨てる() {
        assertEquals("", run("<think>途中まで"))
    }
}
