package com.unchunks.echomark.screenshot

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * ランチャーアイコン(アダプティブアイコンの背景＋前景)を、円・角丸のマスクと単色版で並べて描く。
 * painterResource はアダプティブアイコンの XML を読めないため、層を重ねて再現している。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    @Test
    fun launcherIcon() = screenshot.captureLightDark("launcher_icon") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LayeredIcon(CircleShape)
            LayeredIcon(RoundedCornerShape(24.dp))
            // テーマアイコン(単色版)の見え方。背景は OS が付ける淡い色を想定
            Box(
                Modifier
                    .size(ICON_SIZE)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painterResource(R.drawable.ic_launcher_monochrome),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimaryContainer),
                    modifier = Modifier.requiredSize(LAYER_SIZE)
                )
            }
        }
    }
}

// 実機と同じく、108dp の層の中央 72dp だけがマスクで見える(外周は視差効果用の余白)
private val ICON_SIZE = 96.dp
private val LAYER_SIZE = ICON_SIZE * 108 / 72

@Composable
private fun LayeredIcon(shape: Shape) {
    Box(
        Modifier
            .size(ICON_SIZE)
            .clip(shape),
        contentAlignment = Alignment.Center
    ) {
        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.requiredSize(LAYER_SIZE))
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(LAYER_SIZE))
    }
}
