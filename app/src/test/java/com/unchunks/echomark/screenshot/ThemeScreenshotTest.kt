package com.unchunks.echomark.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** ブランド配色・タイポグラフィの見本。配色を調整したときに全体のバランスを確認する用。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeScreenshotTest {

    @Test
    fun colorRoles() = captureLightDark("theme_color_roles") {
        val c = MaterialTheme.colorScheme
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Swatch("primary", c.primary, c.onPrimary)
            Swatch("primaryContainer", c.primaryContainer, c.onPrimaryContainer)
            Swatch("secondary", c.secondary, c.onSecondary)
            Swatch("secondaryContainer", c.secondaryContainer, c.onSecondaryContainer)
            Swatch("tertiary", c.tertiary, c.onTertiary)
            Swatch("tertiaryContainer", c.tertiaryContainer, c.onTertiaryContainer)
            Swatch("error", c.error, c.onError)
            Swatch("errorContainer", c.errorContainer, c.onErrorContainer)
            Swatch("surfaceContainerLowest", c.surfaceContainerLowest, c.onSurface)
            Swatch("surfaceContainerLow", c.surfaceContainerLow, c.onSurface)
            Swatch("surfaceContainer", c.surfaceContainer, c.onSurface)
            Swatch("surfaceContainerHigh", c.surfaceContainerHigh, c.onSurface)
            Swatch("surfaceContainerHighest", c.surfaceContainerHighest, c.onSurfaceVariant)
            Swatch("inverseSurface", c.inverseSurface, c.inverseOnSurface)
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.outlineVariant))
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
        }
    }

    @Test
    fun typography() = captureLightDark("theme_typography") {
        val t = MaterialTheme.typography
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("見出し headlineSmall", style = t.headlineSmall)
            Text("タイトル titleLarge", style = t.titleLarge)
            Text("カードのタイトル titleMedium", style = t.titleMedium)
            Text(
                "本文 bodyLarge: 保存した記事やメモを AI が要約し、あとから響き返すように思い出させてくれます。",
                style = t.bodyLarge
            )
            Text(
                "本文 bodyMedium: 週に一度、しばらく開いていないブックマークを再発見として届けます。",
                style = t.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("ラベル labelMedium・3日前", style = t.labelMedium)
        }
    }
}

@Composable
private fun Swatch(name: String, container: Color, content: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(container, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(name, color = content, style = MaterialTheme.typography.labelLarge)
    }
}
