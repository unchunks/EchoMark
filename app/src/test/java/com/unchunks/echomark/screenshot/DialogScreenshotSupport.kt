package com.unchunks.echomark.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.isDialog
import com.github.takahirom.roborazzi.captureRoboImage
import com.unchunks.echomark.ui.theme.EchoMarkTheme

/**
 * ダイアログ(別ウィンドウに描かれる)をライト/ダークで撮る。[ScreenshotRule.captureLightDark] のダイアログ版。
 * 保存名は `<name>_light.png` / `<name>_dark.png`。1テストにつき1回だけ呼べる。
 */
fun ScreenshotRule.captureDialogLightDark(name: String, content: @Composable () -> Unit) {
    var dark by mutableStateOf(false)
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
        CompositionLocalProvider(LocalInspectionMode provides true) {
            EchoMarkTheme(darkTheme = dark, dynamicColor = false) {
                content()
            }
        }
    }
    listOf(false to "light", true to "dark").forEach { (isDark, suffix) ->
        dark = isDark
        Snapshot.sendApplyNotifications()
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.onNode(isDialog()).captureRoboImage("${name}_$suffix.png")
    }
}
