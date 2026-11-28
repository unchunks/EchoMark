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
        Triple(c.surfaceVariant, c.onSurface, "surfaceVariant / onSurface(ペーパーのウィジェットの行)"),
        Triple(c.surfaceVariant, c.onSurfaceVariant, "surfaceVariant / onSurfaceVariant"),
        Triple(c.primary, c.onPrimary, "primary / onPrimary(クリアの吹き出し・選んだチップ・追加ボタン、ペーパーの選んだチップ)"),
        Triple(c.primaryContainer, c.onPrimaryContainer, "primaryContainer / onPrimaryContainer(再発見の帯)"),
        Triple(c.secondaryContainer, c.onSecondaryContainer, "secondaryContainer / onSecondaryContainer"),
        Triple(c.tertiaryContainer, c.onTertiaryContainer, "tertiaryContainer / onTertiaryContainer"),
        Triple(c.errorContainer, c.onErrorContainer, "errorContainer / onErrorContainer"),
        Triple(c.surface, c.error, "surface / error"),
        // ui/design の部品が、どのデザインでも載せる組み合わせ
        Triple(c.surfaceContainerLow, c.onSurface, "surfaceContainerLow / onSurface(カード・設定のまとまり)"),
        Triple(c.surfaceContainerHigh, c.onSurface, "surfaceContainerHigh / onSurface(中の区画)"),
        Triple(c.surfaceContainerHigh, c.onSurfaceVariant, "surfaceContainerHigh / onSurfaceVariant(中の区画の補足)")
    )

    /** あるデザインの部品だけが載せる組み合わせ(ui/design の描き分け) */
    private fun designPairs(style: DesignStyle, c: ColorScheme): List<Triple<Color, Color, String>> = when (style) {
        DesignStyle.CLASSIC -> emptyList()
        DesignStyle.ECHO -> listOf(
            Triple(c.surfaceContainerHigh, c.onSurfaceVariant, "surfaceContainerHigh / onSurfaceVariant(チップ)"),
            Triple(c.tertiaryContainer, c.onTertiaryContainer, "tertiaryContainer / onTertiaryContainer(AI のアイコン)")
        )
        DesignStyle.PAPER -> listOf(
            Triple(c.primary, c.onPrimary, "primary / onPrimary(選んだチップ・切り替えボタン)"),
            Triple(c.primaryContainer, c.onPrimaryContainer, "primaryContainer / onPrimaryContainer(自分の吹き出し)")
        )
        DesignStyle.CLEAR -> listOf(
            Triple(c.primaryContainer, c.primary, "primaryContainer / primary(色付きボタン)"),
            Triple(c.surfaceContainerHigh, c.primary, "surfaceContainerHigh / primary(控えめなボタン・AI のアイコン)"),
            Triple(c.surfaceContainerHigh, c.onSurface, "surfaceContainerHigh / onSurface(切り替えボタン・チップ)"),
            Triple(c.primary, c.onPrimary, "primary / onPrimary(自分の吹き出し・選んだチップ・追加ボタン)")
        )
        DesignStyle.SOFT -> listOf(
            Triple(c.surfaceContainerHighest, c.primary, "surfaceContainerHighest / primary(選んだ切り替えボタン)"),
            Triple(c.surface, c.primary, "surface / primary(色付きボタン・追加ボタン)"),
            Triple(c.surfaceContainer, c.primary, "surfaceContainer / primary(選んだチップ)")
        )
    }

    /**
     * 文字以外で形や状態を伝える組み合わせ(地色, 前景, 名前)。WCAG の目安(3:1)。
     * 入力欄の枠・スイッチのオンの帯・選んだタブのアイコンなど、見分けられないと操作できないもの
     */
    private fun nonTextPairs(c: ColorScheme): List<Triple<Color, Color, String>> = listOf(
        Triple(c.surface, c.outline, "surface / outline(入力欄の枠)"),
        Triple(c.surfaceContainerLow, c.outline, "surfaceContainerLow / outline(シート・カードの上の入力欄の枠)"),
        Triple(c.surfaceContainerLow, c.tertiary, "surfaceContainerLow / tertiary(クリアのスイッチのオン)"),
        Triple(c.primaryContainer, c.primary, "primaryContainer / primary(クリアのレールの選んだタブ)"),
        Triple(c.surface, c.primary, "surface / primary(ソフトの選んだもの)")
    )

    /** クラシックはこれまでの配色のまま(変えない)なので、ここでは新しいデザインだけを確かめる */
    @Test
    fun 新しいデザインで文字以外の形と状態のコントラスト比が3以上() {
        val failures = DesignStyle.entries.filter { it != DesignStyle.CLASSIC }.flatMap { style ->
            listOf(false, true).flatMap { dark ->
                nonTextPairs(designColorScheme(style, dark)).mapNotNull { (background, foreground, name) ->
                    val ratio = contrastRatio(background, foreground)
                    if (ratio < 3f) "$style ${if (dark) "dark" else "light"} $name = ${"%.2f".format(ratio)}" else null
                }
            }
        }
        assertTrue("コントラスト比が足りない組み合わせ:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun すべてのデザインで文字のコントラスト比が4_5以上() {
        val failures = DesignStyle.entries.flatMap { style ->
            listOf(false, true).flatMap { dark ->
                val scheme = designColorScheme(style, dark)
                (textPairs(scheme) + designPairs(style, scheme)).mapNotNull { (background, foreground, name) ->
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
