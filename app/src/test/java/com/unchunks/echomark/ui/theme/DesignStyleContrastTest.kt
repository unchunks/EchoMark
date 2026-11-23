package com.unchunks.echomark.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.unchunks.echomark.domain.repository.DesignStyle
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 各デザインの配色で、文字と地色のコントラスト比が WCAG の目安(通常の文字 4.5:1)を満たすこと。
 * 配色を調整したときに、読みにくい組み合わせが紛れ込まないようにする。
 */
class DesignStyleContrastTest {

    /** 画面で文字を載せる組み合わせ(地色, 文字色, 名前) */
    private fun textPairs(c: ColorScheme): List<Triple<Color, Color, String>> = listOf(
        Triple(c.surface, c.onSurface, "surface / onSurface"),
        Triple(c.surface, c.onSurfaceVariant, "surface / onSurfaceVariant"),
        Triple(c.surface, c.primary, "surface / primary(見出し)"),
        Triple(c.surfaceContainerLow, c.onSurfaceVariant, "surfaceContainerLow / onSurfaceVariant"),
        Triple(c.surfaceContainer, c.onSurface, "surfaceContainer / onSurface(エコーのカード)"),
        Triple(c.surfaceContainer, c.onSurfaceVariant, "surfaceContainer / onSurfaceVariant"),
        Triple(c.surfaceContainerLowest, c.onSurfaceVariant, "surfaceContainerLowest / onSurfaceVariant(エコーの要約)"),
        Triple(c.surfaceContainerHighest, c.onSurface, "surfaceContainerHighest / onSurface(選択中のカード)"),
        Triple(c.primary, c.onPrimary, "primary / onPrimary"),
        Triple(c.primaryContainer, c.onPrimaryContainer, "primaryContainer / onPrimaryContainer(再発見の帯)"),
        Triple(c.secondaryContainer, c.onSecondaryContainer, "secondaryContainer / onSecondaryContainer"),
        Triple(c.tertiaryContainer, c.onTertiaryContainer, "tertiaryContainer / onTertiaryContainer"),
        Triple(c.errorContainer, c.onErrorContainer, "errorContainer / onErrorContainer"),
        Triple(c.surface, c.error, "surface / error")
    )

    @Test
    fun すべてのデザインで文字のコントラスト比が4_5以上() {
        val failures = DesignStyle.entries.flatMap { style ->
            listOf(false, true).flatMap { dark ->
                textPairs(designColorScheme(style, dark)).mapNotNull { (background, foreground, name) ->
                    val ratio = contrastRatio(background, foreground)
                    if (ratio < 4.5f) "$style ${if (dark) "dark" else "light"} $name = ${"%.2f".format(ratio)}" else null
                }
            }
        }
        assertTrue("コントラスト比が足りない組み合わせ:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /** ペーパーのカードの罫線は、カードの区切りを伝える要素なので 3:1 の目安には届かなくてよいが、地色と見分けられること */
    @Test
    fun ペーパーの罫線は地色と区別できる() {
        listOf(false, true).forEach { dark ->
            val c = designColorScheme(DesignStyle.PAPER, dark)
            val ratio = contrastRatio(c.surface, c.outlineVariant)
            assertTrue("PAPER ${if (dark) "dark" else "light"} outlineVariant = $ratio", ratio >= 1.3f)
        }
    }

    private fun contrastRatio(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }
}
