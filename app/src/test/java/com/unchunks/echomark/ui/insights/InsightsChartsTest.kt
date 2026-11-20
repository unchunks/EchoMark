package com.unchunks.echomark.ui.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsChartsTest {

    @Test
    fun 目盛りの最大値は四等分しやすい切りのよい値になる() {
        assertEquals(4, niceMax(0))
        assertEquals(4, niceMax(3))
        assertEquals(4, niceMax(4))
        assertEquals(8, niceMax(5))
        assertEquals(20, niceMax(17))
        assertEquals(40, niceMax(33))
        assertEquals(400, niceMax(321))
    }

    @Test
    fun 最大値はいつも元の値以上で四で割り切れる() {
        for (value in 0..5000) {
            val top = niceMax(value)
            assertTrue("value=$value top=$top", top >= value)
            assertEquals("value=$value top=$top", 0, top % 4)
        }
    }
}
