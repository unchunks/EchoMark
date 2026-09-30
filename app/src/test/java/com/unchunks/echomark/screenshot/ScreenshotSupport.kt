package com.unchunks.echomark.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.unchunks.echomark.ui.theme.EchoMarkTheme

/**
 * スクリーンショットテスト共通の描画ヘルパー。
 * [content] をブランドテーマ(ライト/ダーク)で描画し、`<name>_light.png` / `<name>_dark.png` として保存する。
 * 保存先は Roborazzi の出力ディレクトリ(既定 app/build/outputs/roborazzi)。
 *
 * 画像はブランド配色で固定したいので dynamicColor は使わない。
 * Hilt を使わず描画するため、画面は「状態を受け取るだけの Composable」を渡すこと。
 */
fun captureLightDark(
    name: String,
    widthDp: Int = DEFAULT_WIDTH_DP,
    content: @Composable () -> Unit
) {
    listOf(false to "light", true to "dark").forEach { (dark, suffix) ->
        captureRoboImage(filePath = "${name}_$suffix.png") {
            EchoMarkTheme(darkTheme = dark, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(modifier = Modifier.width(widthDp.dp).padding(16.dp)) {
                        content()
                    }
                }
            }
        }
    }
}

/** 一般的なスマートフォンの横幅(dp) */
const val DEFAULT_WIDTH_DP = 400
