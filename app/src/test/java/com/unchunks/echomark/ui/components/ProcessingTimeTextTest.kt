package com.unchunks.echomark.ui.components

import com.unchunks.echomark.domain.bookmark.model.ProcessingProgress
import org.junit.Assert.assertEquals
import org.junit.Test

/** 経過時間・残り時間・進み具合の言い方。 */
class ProcessingTimeTextTest {

    @Test
    fun 経過時間() {
        assertEquals("0秒", formatElapsed(400))
        assertEquals("45秒", formatElapsed(45_000))
        assertEquals("3分12秒", formatElapsed(192_000))
        assertEquals("1時間5分", formatElapsed(65 * 60_000L + 30_000))
    }

    @Test
    fun 残り時間は分単位に切り上げる() {
        assertEquals("1分未満", formatRemaining(59_000))
        assertEquals("約1分", formatRemaining(60_000))
        assertEquals("約2分", formatRemaining(61_000))
        assertEquals("約1時間", formatRemaining(60 * 60_000L))
        assertEquals("約1時間10分", formatRemaining(70 * 60_000L))
    }

    @Test
    fun 進み具合の説明は分かる項目だけを並べる() {
        assertEquals(
            "段階 2/4 ・ この段階 25% ・ 経過 1分20秒 ・ 残り約3分",
            ProcessingProgress(2, 4, 0.25f, 80_000L, 150_000L).summaryText()
        )
        assertEquals("段階 1/2", ProcessingProgress(1, 2, null, null, null).summaryText())
    }
}
