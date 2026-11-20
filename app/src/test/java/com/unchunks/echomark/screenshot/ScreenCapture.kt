package com.unchunks.echomark.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil3.ColorImage
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.github.takahirom.roborazzi.captureRoboImage
import com.unchunks.echomark.ui.theme.EchoMarkTheme

/**
 * 画面全体(Scaffold を含む)を、スマートフォンの画面サイズの枠に入れてライト/ダークで撮る。
 * 部品用の [ScreenshotRule.captureLightDark] と違い、周囲の余白を付けず、高さも固定する。
 * 1テストにつき1回だけ呼べる。
 *
 * @param fontScale 端末の文字サイズ設定(アクセシビリティの確認用。2.0 = 最大級)
 */
@OptIn(ExperimentalCoilApi::class)
fun ScreenshotRule.captureScreenLightDark(
    name: String,
    widthDp: Int = DEFAULT_WIDTH_DP,
    heightDp: Int = SCREEN_HEIGHT_DP,
    fontScale: Float = 1f,
    content: @Composable () -> Unit
) {
    var dark by mutableStateOf(false)
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
        CompositionLocalProvider(
            LocalInspectionMode provides true,
            LocalAsyncImagePreviewHandler provides ScreenFakeImageHandler,
            LocalDensity provides Density(LocalDensity.current.density, fontScale)
        ) {
            EchoMarkTheme(darkTheme = dark, dynamicColor = false) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.testTag(SCREEN_TAG)
                ) {
                    Box(modifier = Modifier.size(widthDp.dp, heightDp.dp)) {
                        content()
                    }
                }
            }
        }
    }
    listOf(false to "light", true to "dark").forEach { (isDark, suffix) ->
        dark = isDark
        Snapshot.sendApplyNotifications()
        composeRule.mainClock.advanceTimeBy(SCREEN_SETTLE_MILLIS)
        composeRule.onNodeWithTag(SCREEN_TAG).captureRoboImage("${name}_$suffix.png")
    }
}

/** 一般的なスマートフォンの画面の高さ(dp)。ステータスバー等を除いた、アプリが使える範囲の目安 */
const val SCREEN_HEIGHT_DP = 800

private const val SCREEN_TAG = "screen_root"
private const val SCREEN_SETTLE_MILLIS = 800L

@OptIn(ExperimentalCoilApi::class)
private val ScreenFakeImageHandler = AsyncImagePreviewHandler { ColorImage(0xFF6F8FA6.toInt()) }
