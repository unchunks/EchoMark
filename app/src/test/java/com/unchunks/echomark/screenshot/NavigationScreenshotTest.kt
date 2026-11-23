package com.unchunks.echomark.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.repository.DesignStyle
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

    /** エコーは画面の端から離した、角の大きく丸い浮いたバー */
    @Test
    fun navigationBarEcho() = screenshot.captureLightDark("navigation_bar_echo", designStyle = DesignStyle.ECHO) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EchoMarkNavigationBar(selectedRoute = "bookmarks", onNavigate = {})
            EchoMarkNavigationBar(selectedRoute = "chat", onNavigate = {})
        }
    }

    /** エコーの浮いたバーでも、文字を最大(2倍)にしたとき2行のラベルが切れないこと */
    @Test
    fun navigationBarEchoLargeFont() =
        screenshot.captureLightDark("navigation_bar_echo_large_font", designStyle = DesignStyle.ECHO) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                EchoMarkNavigationBar(selectedRoute = "explore", onNavigate = {})
            }
        }

    /** クリアは白いタブバーに区切り線。選んだタブは青いアイコンと文字だけで示す */
    @Test
    fun navigationBarClear() = screenshot.captureLightDark("navigation_bar_clear", designStyle = DesignStyle.CLEAR) {
        EchoMarkNavigationBar(selectedRoute = "bookmarks", onNavigate = {})
    }

    /** ソフトは地色と同じ色の浮いたバーを光と影で押し出す */
    @Test
    fun navigationBarSoft() = screenshot.captureLightDark("navigation_bar_soft", designStyle = DesignStyle.SOFT) {
        EchoMarkNavigationBar(selectedRoute = "chat", onNavigate = {})
    }

    /** ペーパーは紙面と同じ地色に罫線1本 */
    @Test
    fun navigationBarPaper() = screenshot.captureLightDark("navigation_bar_paper", designStyle = DesignStyle.PAPER) {
        EchoMarkNavigationBar(selectedRoute = "bookmarks", onNavigate = {})
    }

    /** 広い画面で左に出すナビゲーションレール */
    @Test
    fun navigationRail() = screenshot.captureLightDark("navigation_rail", widthDp = 240) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EchoMarkNavigationRail(selectedRoute = "bookmarks", onNavigate = {}, modifier = Modifier.height(320.dp))
            EchoMarkNavigationRail(selectedRoute = "settings", onNavigate = {}, modifier = Modifier.height(320.dp))
        }
    }

    /** エコーのレールは、ボトムバーと同じく画面の端から離した角の丸い浮いたレール */
    @Test
    fun navigationRailEcho() = captureRail("navigation_rail_echo", DesignStyle.ECHO)

    /** ペーパーのレールは、紙面と同じ地色に右端の罫線1本 */
    @Test
    fun navigationRailPaper() = captureRail("navigation_rail_paper", DesignStyle.PAPER)

    /** クリアのレールは、白い帯に右端の細い区切り線。選んだタブは淡い青の丸と青いアイコン */
    @Test
    fun navigationRailClear() = captureRail("navigation_rail_clear", DesignStyle.CLEAR)

    /** ソフトのレールは、地色と同じ色の浮いたレールを光と影で押し出す */
    @Test
    fun navigationRailSoft() = captureRail("navigation_rail_soft", DesignStyle.SOFT)

    private fun captureRail(name: String, style: DesignStyle) =
        screenshot.captureLightDark(name, widthDp = 160, designStyle = style) {
            EchoMarkNavigationRail(selectedRoute = "bookmarks", onNavigate = {}, modifier = Modifier.height(320.dp))
        }
}
