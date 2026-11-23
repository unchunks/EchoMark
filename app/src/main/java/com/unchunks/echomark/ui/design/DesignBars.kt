package com.unchunks.echomark.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.repository.DesignStyle
import com.unchunks.echomark.ui.theme.EchoMarkDesign
import com.unchunks.echomark.ui.theme.LocalDesignStyle
import com.unchunks.echomark.ui.theme.softInset

/**
 * 画面の下に置く操作のバー(複数選択の操作・再生の操作・探す画面の下のボタン)。上端の区切りと地色はデザインに従う。
 * - ペーパー: 紙の地に上端の罫線
 * - クリア: 白い(ダークは灰色の)地に上端の細い区切り線(iPhone の下のツールバー)
 * - ソフト: 地と同じ色で、上端に柔らかい影を落とす
 * - エコー: トーナルな面(影なし)
 * @param classic クラシックでの地色(これまでの色)
 */
@Composable
fun DesignBottomBar(
    modifier: Modifier = Modifier,
    classic: Color = MaterialTheme.colorScheme.surfaceContainer,
    classicTonalElevation: Dp = 3.dp,
    classicShadowElevation: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    val c = MaterialTheme.colorScheme
    val dark = EchoMarkDesign.isDark
    when (LocalDesignStyle.current) {
        DesignStyle.CLASSIC -> Surface(
            modifier = modifier,
            color = classic,
            tonalElevation = classicTonalElevation,
            shadowElevation = classicShadowElevation,
            content = content
        )
        DesignStyle.ECHO -> Surface(modifier = modifier, color = c.surfaceContainer, content = content)
        DesignStyle.PAPER -> Surface(modifier = modifier.topRule(c.outlineVariant), color = c.surface, content = content)
        DesignStyle.CLEAR -> Surface(modifier = modifier.topRule(c.outlineVariant), color = c.surfaceContainerLow, content = content)
        DesignStyle.SOFT -> Surface(modifier = modifier.topShade(dark), color = c.surface, content = content)
    }
}

private fun Modifier.topRule(color: Color): Modifier = drawWithContent {
    drawContent()
    val y = 0.5.dp.toPx()
    drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
}

/** バーの上(中身の下端)に、柔らかい影の帯を描く */
private fun Modifier.topShade(dark: Boolean): Modifier = drawWithContent {
    val shade = if (dark) Color(0x80121417) else Color(0x59A3AFC2)
    val depth = 10.dp.toPx()
    drawRect(
        brush = Brush.verticalGradient(listOf(Color.Transparent, shade), startY = -depth, endY = 0f),
        topLeft = Offset(0f, -depth),
        size = Size(size.width, depth)
    )
    drawContent()
}

/**
 * 文字を入れる欄(質問の入力・検索)の描き方。M3 の TextField の形・地色に渡し、[depth] は欄の Modifier に付ける。
 * - ソフト: 地と同じ色をくぼませた欄(押し込まれた入力欄)
 * - ペーパー: 一段濃い紙の欄
 * - そのほか: クラシックでの地色のまま、角丸だけデザインに従う
 */
@Immutable
data class FieldStyle(val shape: Shape, val containerColor: Color, val depth: Modifier)

/**
 * @param classicContainer クラシックでの欄の地色(これまでの色)
 * @param classicCorner クラシックでの角丸の半径
 */
@Composable
fun designFieldStyle(classicContainer: Color, classicCorner: Dp = 28.dp): FieldStyle {
    val c = MaterialTheme.colorScheme
    val shape = EchoMarkDesign.roundedShape(classicCorner)
    return when (LocalDesignStyle.current) {
        DesignStyle.SOFT -> FieldStyle(shape, c.surface, Modifier.softInset(shape, EchoMarkDesign.isDark))
        DesignStyle.PAPER -> FieldStyle(shape, c.surfaceContainerLow, Modifier)
        DesignStyle.CLEAR -> FieldStyle(shape, c.surfaceContainerHigh, Modifier)
        else -> FieldStyle(shape, classicContainer, Modifier)
    }
}
