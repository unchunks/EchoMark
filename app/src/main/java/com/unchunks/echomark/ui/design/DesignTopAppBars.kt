package com.unchunks.echomark.ui.design

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.repository.DesignStyle
import com.unchunks.echomark.ui.theme.EchoMarkDesign
import com.unchunks.echomark.ui.theme.LocalDesignStyle

// 画面の上のバー。デザインごとに地色と、下端の区切り(罫線・影)を描き分ける。
// - クラシック・エコー: M3 の既定(スクロールすると一段濃い面になる)
// - ペーパー: 紙の地のまま、下端にいつも罫線を引く(紙面の見出しの罫)
// - クリア: 淡い灰色の地。中身がバーの下に潜ると白くなり、下端に細い区切り線を出す(iPhone の画面の上のバー)
// - ソフト: 地と同じ色のまま。中身がバーの下に潜ると、下端に柔らかい影を落とす

/** 画面の上のバーの色。デザインに従う */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun designTopAppBarColors(): TopAppBarColors {
    val c = MaterialTheme.colorScheme
    return when (LocalDesignStyle.current) {
        DesignStyle.CLASSIC, DesignStyle.ECHO -> TopAppBarDefaults.topAppBarColors()
        DesignStyle.PAPER, DesignStyle.SOFT ->
            TopAppBarDefaults.topAppBarColors(containerColor = c.surface, scrolledContainerColor = c.surface)
        DesignStyle.CLEAR ->
            TopAppBarDefaults.topAppBarColors(containerColor = c.surface, scrolledContainerColor = c.surfaceContainerLow)
    }
}

/**
 * バーの下端の区切り。スクロールの具合([scrollBehavior] の重なり)は描くときに読むので、スクロールで作り直さない。
 * スクロールの具合が分からない画面(scrollBehavior なし)では、クリアとソフトは区切りを出さない。
 */
@OptIn(ExperimentalMaterial3Api::class)
private fun Modifier.topBarEdge(
    style: DesignStyle,
    rule: Color,
    dark: Boolean,
    scrollBehavior: TopAppBarScrollBehavior?
): Modifier {
    return when (style) {
        DesignStyle.CLASSIC, DesignStyle.ECHO -> this
        DesignStyle.PAPER -> drawWithContent {
            drawContent()
            val y = size.height - 0.5.dp.toPx()
            drawLine(rule, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        DesignStyle.CLEAR -> drawWithContent {
            drawContent()
            val fraction = scrollBehavior?.state?.overlappedFraction ?: 0f
            if (fraction > 0.01f) {
                val y = size.height - 0.5.dp.toPx()
                drawLine(rule.copy(alpha = fraction.coerceAtMost(1f)), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
        }
        DesignStyle.SOFT -> drawWithContent {
            drawContent()
            val fraction = scrollBehavior?.state?.overlappedFraction ?: 0f
            if (fraction > 0.01f) {
                // バーの下(中身の上)に、柔らかい影の帯を描く
                val shade = if (dark) Color(0x80121417) else Color(0x59A3AFC2)
                val depth = 10.dp.toPx()
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(shade.copy(alpha = shade.alpha * fraction.coerceAtMost(1f)), Color.Transparent),
                        startY = size.height,
                        endY = size.height + depth
                    ),
                    topLeft = Offset(0f, size.height),
                    size = Size(size.width, depth)
                )
            }
        }
    }
}

/** 画面の上のバー([TopAppBar] と同じ引数)。地色と下端の区切りはデザインに従う */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EchoMarkTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    colors: TopAppBarColors = designTopAppBarColors(),
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    TopAppBar(
        title = title,
        modifier = modifier.topBarEdge(LocalDesignStyle.current, MaterialTheme.colorScheme.outlineVariant, EchoMarkDesign.isDark, scrollBehavior),
        navigationIcon = navigationIcon,
        actions = actions,
        windowInsets = windowInsets,
        colors = colors,
        scrollBehavior = scrollBehavior
    )
}

/** 大きな見出しの上のバー([LargeTopAppBar] と同じ引数)。地色と下端の区切りはデザインに従う */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EchoMarkLargeTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    colors: TopAppBarColors = designTopAppBarColors(),
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    LargeTopAppBar(
        title = title,
        modifier = modifier.topBarEdge(LocalDesignStyle.current, MaterialTheme.colorScheme.outlineVariant, EchoMarkDesign.isDark, scrollBehavior),
        navigationIcon = navigationIcon,
        actions = actions,
        windowInsets = windowInsets,
        colors = colors,
        scrollBehavior = scrollBehavior
    )
}

/** 見出しを中央に置く上のバー([CenterAlignedTopAppBar] と同じ引数)。地色と下端の区切りはデザインに従う */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EchoMarkCenterAlignedTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    colors: TopAppBarColors = designTopAppBarColors(),
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    CenterAlignedTopAppBar(
        title = title,
        modifier = modifier.topBarEdge(LocalDesignStyle.current, MaterialTheme.colorScheme.outlineVariant, EchoMarkDesign.isDark, scrollBehavior),
        navigationIcon = navigationIcon,
        actions = actions,
        windowInsets = windowInsets,
        colors = colors,
        scrollBehavior = scrollBehavior
    )
}

/**
 * 画面の上のバーの代わりに置く、自前の上の帯(一覧の検索中の検索欄など)。地色と下端の区切りは上のバーと同じ。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesignTopBarSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        color = designTopAppBarColors().containerColor,
        modifier = modifier.topBarEdge(LocalDesignStyle.current, MaterialTheme.colorScheme.outlineVariant, EchoMarkDesign.isDark, null),
        content = content
    )
}
