package com.unchunks.echomark.ui.navigation

import androidx.compose.material3.adaptive.HingeInfo
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.ui.geometry.Rect
import androidx.window.core.layout.WindowSizeClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 画面の大きさ・折りたたみの姿勢から、タブの出し方と 2 画面にするかを決める([adaptiveLayoutOf])。 */
class AdaptiveLayoutTest {

    private fun layoutOf(widthDp: Int, heightDp: Int, posture: Posture = Posture()) =
        adaptiveLayoutOf(WindowAdaptiveInfo(WindowSizeClass(widthDp.toFloat(), heightDp.toFloat()), posture))

    @Test
    fun スマートフォンの縦向きはボトムバーで1画面() {
        val layout = layoutOf(411, 891)
        assertEquals(NavigationLayout.BAR, layout.navigation)
        assertFalse(layout.isTwoPane)
    }

    @Test
    fun スマートフォンの横向きはレールにするが高さが低いので1画面() {
        val layout = layoutOf(891, 411)
        assertEquals(NavigationLayout.RAIL, layout.navigation)
        assertFalse(layout.isTwoPane)
    }

    @Test
    fun 縦向きのタブレットはレールで1画面() {
        val layout = layoutOf(800, 1280)
        assertEquals(NavigationLayout.RAIL, layout.navigation)
        assertFalse(layout.isTwoPane)
    }

    @Test
    fun 折りたたみを開いたときと横向きのタブレットは2画面() {
        // Pixel 10 Pro Fold の内側の画面(約 851dp × 882dp)
        assertTrue(layoutOf(851, 882).isTwoPane)
        assertTrue(layoutOf(1280, 800).isTwoPane)
    }

    @Test
    fun とても広い画面でもペインは2つまで() {
        assertEquals(2, layoutOf(1920, 1080).paneDirective.maxHorizontalPartitions)
    }

    @Test
    fun 縦の折り目が画面を分けているときは幅が狭くても折り目の左右に分ける() {
        val hinge = HingeInfo(
            bounds = Rect(left = 700f, top = 0f, right = 740f, bottom = 1800f),
            isFlat = false,
            isVertical = true,
            isSeparating = true,
            isOccluding = true
        )
        val layout = layoutOf(720, 900, Posture(hingeList = listOf(hinge)))
        assertTrue(layout.isTwoPane)
        assertEquals(listOf(hinge.bounds), layout.paneDirective.excludedBounds)
    }
}
