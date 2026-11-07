package com.unchunks.echomark.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import coil3.ColorImage
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.github.takahirom.roborazzi.captureRoboImage
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * スクリーンショットテスト共通のルール。テストクラスで `@get:Rule val screenshot = ScreenshotRule()` として使う。
 *
 * - ブランドテーマ(ライト/ダーク)で描画し、`<name>_light.png` / `<name>_dark.png` を
 *   Roborazzi の出力ディレクトリ(既定 app/build/outputs/roborazzi)に保存する。
 * - Compose の時計は手動で進める(autoAdvance = false)。プログレス表示などの無限アニメーションがあると、
 *   自動で進めた場合に「アイドル待ち」が終わらずテストが止まるため。
 * - プレビューと同じ扱い(LocalInspectionMode = true)にし、Coil の画像は通信せず単色の仮画像で描く。
 * - 画像はブランド配色で固定したいので dynamicColor は使わない。
 * - Hilt を使わず描画するため、画面は「状態を受け取るだけの Composable」を渡すこと。
 */
class ScreenshotRule : TestRule {

    val composeRule = createComposeRule()

    override fun apply(base: Statement, description: Description): Statement =
        composeRule.apply(base, description)

    /** 1テストにつき1回だけ呼べる(Compose のテストルールは setContent を1回しか許さないため)。 */
    @OptIn(ExperimentalCoilApi::class)
    fun captureLightDark(
        name: String,
        widthDp: Int = DEFAULT_WIDTH_DP,
        content: @Composable () -> Unit
    ) {
        var dark by mutableStateOf(false)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides FakeImageHandler
            ) {
                EchoMarkTheme(darkTheme = dark, dynamicColor = false) {
                    Surface(
                        color = MaterialTheme.colorScheme.background,
                        modifier = Modifier.testTag(CAPTURE_TAG)
                    ) {
                        Box(modifier = Modifier.width(widthDp.dp).padding(16.dp)) {
                            content()
                        }
                    }
                }
            }
        }
        listOf(false to "light", true to "dark").forEach { (isDark, suffix) ->
            dark = isDark
            // テストスレッドからの状態変更を Compose に通知し、再コンポーズと画像の読み込み(仮画像)が終わるまで時計を進める
            Snapshot.sendApplyNotifications()
            composeRule.mainClock.advanceTimeBy(SETTLE_MILLIS)
            composeRule.onNodeWithTag(CAPTURE_TAG).captureRoboImage("${name}_$suffix.png")
        }
    }

    private companion object {
        const val CAPTURE_TAG = "screenshot_root"
        const val SETTLE_MILLIS = 500L
    }
}

/** ネットワーク画像の代わりに描く仮画像(写真っぽい中間色の単色) */
@OptIn(ExperimentalCoilApi::class)
private val FakeImageHandler = AsyncImagePreviewHandler { ColorImage(0xFF6F8FA6.toInt()) }

/** 一般的なスマートフォンの横幅(dp) */
const val DEFAULT_WIDTH_DP = 400
