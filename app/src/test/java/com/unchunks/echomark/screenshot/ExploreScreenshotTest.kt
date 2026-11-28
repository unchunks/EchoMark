package com.unchunks.echomark.screenshot

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.ui.explore.ExploreActions
import com.unchunks.echomark.ui.explore.ExploreScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 「見つける」タブ(読む・ふり返るの大きなカードと、機能のタイル)の見た目。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExploreScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    @Test
    fun explore() = screenshot.captureScreenLightDark("explore") {
        ExploreScreen(actions = ExploreActions())
    }

    /** 文字を大きくしているときは列を減らし、タイトルが途中で切れないこと */
    @Test
    fun exploreLargeFont() = screenshot.captureScreenLightDark("explore_large_font", fontScale = 2f) {
        ExploreScreen(actions = ExploreActions())
    }

    /** 広い画面(タブレット)では 3 列に並べる */
    @Test
    @Config(qualifiers = "w940dp-h1000dp-xhdpi")
    fun exploreWide() = screenshot.captureScreenLightDark("explore_wide", widthDp = 840, heightDp = 600) {
        ExploreScreen(actions = ExploreActions())
    }
}
