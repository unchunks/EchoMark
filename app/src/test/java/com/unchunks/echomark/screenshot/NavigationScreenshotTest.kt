package com.unchunks.echomark.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.ui.navigation.EchoMarkNavigationBar
import com.unchunks.echomark.ui.navigation.EchoMarkNavigationRail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** ボトムバー・ナビゲーションレール(選択中タブの塗りアイコン・ラベル)の見た目。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    @Test
    fun navigationBar() = screenshot.captureLightDark("navigation_bar") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EchoMarkNavigationBar(selectedRoute = "bookmarks", onNavigate = {})
            EchoMarkNavigationBar(selectedRoute = "chat", onNavigate = {})
        }
    }

    /** 広い画面で左に出すナビゲーションレール */
    @Test
    fun navigationRail() = screenshot.captureLightDark("navigation_rail", widthDp = 240) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EchoMarkNavigationRail(selectedRoute = "bookmarks", onNavigate = {}, modifier = Modifier.height(320.dp))
            EchoMarkNavigationRail(selectedRoute = "settings", onNavigate = {}, modifier = Modifier.height(320.dp))
        }
    }
}
