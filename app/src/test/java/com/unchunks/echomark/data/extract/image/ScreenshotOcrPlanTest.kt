package com.unchunks.echomark.data.extract.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotOcrPlanTest {

    @Test
    fun 普通の大きさの画像は帯に分けない() {
        assertTrue(planBands(1080, 2400).isEmpty())
        assertTrue(planBands(1080, 3000).isEmpty())
        assertTrue(planBands(4000, 3000).isEmpty())
        assertTrue(planBands(0, 100).isEmpty())
    }

    @Test
    fun 長辺が大きくても細長くなければ帯に分けない() {
        assertTrue(planBands(3000, 4000).isEmpty())
    }

    @Test
    fun 長いスクリーンショットは短い辺の約1_8倍の帯に少し重ねて分ける() {
        val bands = planBands(1080, 6000)

        assertEquals(0, bands.first().start)
        assertEquals(6000, bands.last().end)
        // 帯の長さは縮小されない範囲(長辺 2048 以下)で、隣り合う帯は重なる
        assertTrue(bands.all { it.end - it.start <= 2048 * 5 / 4 })
        bands.zipWithNext().forEach { (a, b) -> assertTrue("重なる: $a $b", b.start < a.end) }
        // 全体を隙間なく覆う
        bands.zipWithNext().forEach { (a, b) -> assertTrue(b.start <= a.end) }
    }

    @Test
    fun 横に長い画像は横方向の帯に分ける() {
        val bands = planBands(6000, 1080)

        assertEquals(6000, bands.last().end)
        assertTrue(bands.size > 1)
    }

    @Test
    fun 長すぎて帯の数の上限を超えるときは分けない() {
        assertTrue(planBands(1080, 200_000).isEmpty())
    }

    @Test
    fun 最後の帯が短いときは前の帯に含める() {
        val bands = planBands(1000, 3500)

        // 最後の帯が帯の長さの 1/4 に満たない半端なものになっていない
        val length = bands.first().end - bands.first().start
        assertTrue(bands.last().end - bands.last().start >= length / 4)
        assertEquals(3500, bands.last().end)
    }

    @Test
    fun 帯の結果をつなぐとき重なりの二重の行を取り除く() {
        val merged = mergeBandTexts(listOf("a\nb\nc", "b\nc\nd\ne", "e\nf"))

        assertEquals("a\nb\nc\nd\ne\nf", merged)
    }

    @Test
    fun 重なりが無ければそのままつなぐ() {
        assertEquals("a\nb\nc\nd", mergeBandTexts(listOf("a\nb", "c\nd")))
    }

    @Test
    fun 空の結果と空行は無視する() {
        assertEquals("a\nb", mergeBandTexts(listOf("a\n\n", "", "  \nb")))
        assertEquals("", mergeBandTexts(emptyList()))
    }

    @Test
    fun 同じ行が離れて現れても取り除かない() {
        assertEquals("ok\nx\nok", mergeBandTexts(listOf("ok\nx", "ok")))
    }

    @Test
    fun スクリーンショットらしい画像の判定() {
        assertTrue(looksLikeScreenshot("png", 1080, 2400, hasCameraInfo = false))
        assertTrue(looksLikeScreenshot("PNG", 2400, 1080, hasCameraInfo = false))
        assertTrue(looksLikeScreenshot("png", 1080, 9000, hasCameraInfo = false))
    }

    @Test
    fun カメラの情報がある画像や縦横比が普通の画像やPNG以外はスクリーンショットとしない() {
        assertFalse(looksLikeScreenshot("png", 1080, 2400, hasCameraInfo = true))
        assertFalse(looksLikeScreenshot("png", 1000, 1000, hasCameraInfo = false))
        assertFalse(looksLikeScreenshot("png", 4000, 3000, hasCameraInfo = false))
        assertFalse(looksLikeScreenshot("jpg", 1080, 2400, hasCameraInfo = false))
        assertFalse(looksLikeScreenshot(null, 1080, 2400, hasCameraInfo = false))
        assertFalse(looksLikeScreenshot("png", 0, 2400, hasCameraInfo = false))
    }
}
