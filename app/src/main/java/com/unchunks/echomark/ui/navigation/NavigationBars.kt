package com.unchunks.echomark.ui.navigation

import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.NavigationRailItemColors
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.repository.DesignStyle
import com.unchunks.echomark.ui.theme.EchoMarkDesign
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import com.unchunks.echomark.ui.theme.softRaised

/**
 * ボトムバー。[selectedRoute] のタブを選択状態(塗りアイコン)にし、タップで [onNavigate] にルートを渡す。
 * 状態を受け取るだけにしてあるので、スクリーンショットテストで単体描画できる。
 */
@Composable
internal fun EchoMarkNavigationBar(
    selectedRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 狭い画面で長いラベルが折り返しても、各項目のアイコンとラベルの開始位置をそろえる。
    val labelLines = if (LocalConfiguration.current.screenWidthDp < 440 && LocalDensity.current.fontScale >= 1.5f) 2 else 1
    val colors = MaterialTheme.colorScheme
    when (EchoMarkDesign.style) {
        // エコーは画面の端から離した、角の丸い浮いたバーにする
        DesignStyle.ECHO -> FloatingNavigationBar(
            selectedRoute, onNavigate, labelLines, modifier,
            containerColor = colors.surfaceContainerHigh,
            shadowElevation = 3.dp
        )
        // ソフトは地色と同じ色の浮いたバーを、光と影で押し出す
        DesignStyle.SOFT -> FloatingNavigationBar(
            selectedRoute, onNavigate, labelLines, modifier,
            containerColor = colors.surface,
            shadowElevation = 0.dp,
            raised = Modifier.softRaised(RoundedCornerShape(FLOATING_BAR_CORNER), EchoMarkDesign.isDark)
        )
        // ペーパーは帯の色を付けず、紙面と同じ地色に罫線1本で区切る
        DesignStyle.PAPER -> HairlineNavigationBar(selectedRoute, onNavigate, labelLines, modifier, containerColor = colors.surface)
        // クリアはガラスのバー。アプリの枠(EchoMarkAppScaffold)では中身の上に重ね、裏の中身をぼかして映す。
        // ここ(単体で描くとき)は映す中身が無いので、ガラスの色と光だけを描く
        DesignStyle.CLEAR -> GlassNavigationBar(selectedRoute, onNavigate, modifier = modifier, labelLines = labelLines)
        DesignStyle.CLASSIC -> NavigationBar(modifier = modifier) {
            TopLevelItems(selectedRoute, onNavigate, labelLines)
        }
    }
}

/** 上端に罫線1本を引いたボトムバー(ペーパー・クリア) */
@Composable
private fun HairlineNavigationBar(
    selectedRoute: String?,
    onNavigate: (String) -> Unit,
    labelLines: Int,
    modifier: Modifier,
    containerColor: Color,
    itemColors: NavigationBarItemColors? = null
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    NavigationBar(
        modifier = modifier.drawBehind { drawLine(outline, Offset.Zero, Offset(size.width, 0f), strokeWidth = 1.dp.toPx()) },
        containerColor = containerColor
    ) {
        TopLevelItems(selectedRoute, onNavigate, labelLines, itemColors)
    }
}

/**
 * 画面の左右と下から離した、角の大きく丸い浮いたバー(エコー・ソフト。Material 3 Expressive の浮いたツールバーの考え方)。
 * 置き場所は通常のボトムバーと同じ Scaffold の bottomBar なので、中身の余白とシステムバーのインセットの扱いは変わらない
 * (ジェスチャーナビゲーションの帯の分は、バーの外側の余白として取る)。
 * [raised] はバーの外に描く影(ソフトの光と影)。外側の余白の中に収まる。
 */
@Composable
private fun FloatingNavigationBar(
    selectedRoute: String?,
    onNavigate: (String) -> Unit,
    labelLines: Int,
    modifier: Modifier,
    containerColor: Color,
    shadowElevation: Dp,
    raised: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(NavigationBarDefaults.windowInsets)
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
    ) {
        Surface(
            // 両端を半円(CircleShape)にすると、文字を大きくしてバーが高くなったとき端のラベルが丸みに欠けるので、角丸は固定にする
            shape = RoundedCornerShape(FLOATING_BAR_CORNER),
            color = containerColor,
            shadowElevation = shadowElevation,
            modifier = raised.fillMaxWidth()
        ) {
            NavigationBar(
                modifier = Modifier.padding(horizontal = 8.dp),
                containerColor = Color.Transparent,
                tonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0)
            ) {
                TopLevelItems(selectedRoute, onNavigate, labelLines)
            }
        }
    }
}

private val FLOATING_BAR_CORNER = 32.dp

/**
 * クリア(iPhone 風)のボトムバー。リキッドグラスのように、中身の上に浮いたガラスの板にする。
 * - 裏の中身([backdrop]。アプリの枠が記録したもの)をぼかして映し、その上に白(ダークは黒)を薄く重ねる
 * - 上側に光の映り込み、縁に明るい線(ガラスの厚みの反射)を描き、下に柔らかい影を落とす
 * - 選んだタブは、ガラスの中のもう一枚の小さなガラス(明るい丸)と青いアイコンで示す
 *
 * 文字は裏に何があっても読めるよう、重ねる色を濃いめ(ライト 72%・ダーク 62%)にし、ラベルは本文色にする。
 * ぼかし(RenderEffect)は Android 12 以上だけ効く。それより前は、重ねた色越しに中身がそのまま薄く見える。
 *
 * @param backdropOrigin 記録した中身の左上の位置(画面の左上から)
 * @param onOverlapChanged バー(システムのナビゲーションバーの分を除く)の高さ。中身の下端をどれだけ覆うかに使う
 */
@Composable
internal fun GlassNavigationBar(
    selectedRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    labelLines: Int = glassLabelLines(),
    backdrop: GraphicsLayer? = null,
    backdropOrigin: () -> Offset = { Offset.Zero },
    onOverlapChanged: (Int) -> Unit = {}
) {
    val dark = EchoMarkDesign.isDark
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(FLOATING_BAR_CORNER)
    val tint = if (dark) Color(0xFF1C1C1E).copy(alpha = 0.62f) else Color.White.copy(alpha = 0.72f)
    // 上から差す光の映り込みと、縁の反射。左上と右下を明るく、横の中ほどは薄くしてガラスの丸みに見せる
    val sheen = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (dark) 0.10f else 0.45f),
        0.5f to Color.Transparent
    )
    val rim = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = if (dark) 0.35f else 0.9f),
            Color.White.copy(alpha = if (dark) 0.06f else 0.25f),
            Color.White.copy(alpha = if (dark) 0.22f else 0.7f)
        )
    )
    var barOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.fillMaxWidth().windowInsetsPadding(NavigationBarDefaults.windowInsets)) {
        Box(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { onOverlapChanged(it.height) }
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .dropShadow(
                        shape,
                        Shadow(
                            radius = 24.dp,
                            color = Color.Black.copy(alpha = if (dark) 0.5f else 0.14f),
                            offset = DpOffset(0.dp, 8.dp)
                        )
                    )
                    .onGloballyPositioned { barOrigin = it.positionInRoot() }
            ) {
                if (backdrop != null) {
                    // 裏の中身を、バーの位置に合わせてずらして描き、ぼかす
                    Box(
                        Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                renderEffect = BlurEffect(GLASS_BLUR.toPx(), GLASS_BLUR.toPx(), TileMode.Clamp)
                                this.shape = shape
                                clip = true
                            }
                            .drawBehind {
                                val offset = backdropOrigin() - barOrigin
                                translate(offset.x, offset.y) { drawLayer(backdrop) }
                            }
                    )
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(shape)
                        .background(tint)
                        .background(sheen)
                        .border(1.dp, rim, shape)
                )
                NavigationBar(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp,
                    windowInsets = WindowInsets(0, 0, 0, 0)
                ) {
                    TopLevelItems(
                        selectedRoute, onNavigate, labelLines,
                        NavigationBarItemDefaults.colors(
                            selectedIconColor = colors.primary,
                            selectedTextColor = colors.onSurface,
                            // 選んだタブの小さなガラス
                            indicatorColor = Color.White.copy(alpha = if (dark) 0.16f else 0.85f),
                            unselectedIconColor = colors.onSurfaceVariant,
                            unselectedTextColor = colors.onSurface
                        )
                    )
                }
            }
        }
    }
}

/** ガラスのバーのぼかしの強さ */
private val GLASS_BLUR = 24.dp

@Composable
private fun glassLabelLines(): Int =
    if (LocalConfiguration.current.screenWidthDp < 440 && LocalDensity.current.fontScale >= 1.5f) 2 else 1

/** ボトムバーのタブ。狭い画面で長いラベルが折り返しても、各項目のアイコンとラベルの開始位置をそろえる([labelLines]) */
@Composable
private fun RowScope.TopLevelItems(
    selectedRoute: String?,
    onNavigate: (String) -> Unit,
    labelLines: Int,
    itemColors: NavigationBarItemColors? = null
) {
    TopLevelDestination.entries.forEach { destination ->
        val selected = destination.route == selectedRoute
        NavigationBarItem(
            selected = selected,
            onClick = { onNavigate(destination.route) },
            colors = itemColors ?: NavigationBarItemDefaults.colors(),
            icon = { TopLevelIcon(destination, selected) },
            label = {
                Text(
                    text = destination.label,
                    textAlign = TextAlign.Center,
                    minLines = labelLines
                )
            }
        )
    }
}

/**
 * レールの中身の側の端(左から右に書く言語では右端)に引く区切り線(ペーパー・クリア)。
 * レールの地色に隠れないよう、地色と中身を描いた後に重ねる。
 */
private fun Modifier.railEndRule(color: Color): Modifier = drawWithContent {
    drawContent()
    val half = 0.5.dp.toPx()
    val x = if (layoutDirection == LayoutDirection.Ltr) size.width - half else half
    drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
}

/**
 * ナビゲーションレール(広い画面で左に縦に並べるタブ)。並びと見た目はボトムバー([EchoMarkNavigationBar])と同じ。
 * 項目は上から並べる(親指の届く下側ではなく、視線の起点の左上に置く)。
 */
@Composable
internal fun EchoMarkNavigationRail(
    selectedRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val items: @Composable ColumnScope.(NavigationRailItemColors) -> Unit = { itemColors ->
        TopLevelDestination.entries.forEach { destination ->
            val selected = destination.route == selectedRoute
            NavigationRailItem(
                selected = selected,
                onClick = { onNavigate(destination.route) },
                icon = { TopLevelIcon(destination, selected) },
                label = { Text(destination.label) },
                colors = itemColors
            )
        }
    }
    // ボトムバーと同じ描き分けを、縦のレールにも当てる
    when (EchoMarkDesign.style) {
        DesignStyle.CLASSIC -> NavigationRail(modifier = modifier) { items(NavigationRailItemDefaults.colors()) }
        // エコー・ソフトは、画面の端から離した角の丸い浮いたレール(エコーはトーナルな面、ソフトは光と影)
        DesignStyle.ECHO, DesignStyle.SOFT -> {
            val soft = EchoMarkDesign.style == DesignStyle.SOFT
            val shape = RoundedCornerShape(FLOATING_BAR_CORNER)
            Box(
                modifier
                    .windowInsetsPadding(NavigationRailDefaults.windowInsets)
                    .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)
            ) {
                NavigationRail(
                    modifier = Modifier
                        .fillMaxHeight()
                        .then(if (soft) Modifier.softRaised(shape, EchoMarkDesign.isDark) else Modifier)
                        .clip(shape),
                    containerColor = if (soft) colors.surface else colors.surfaceContainer,
                    windowInsets = WindowInsets(0, 0, 0, 0)
                ) {
                    Spacer(Modifier.height(8.dp))
                    items(
                        // ソフトの選んだタブは、押し込んだように一段濃くする
                        if (soft) NavigationRailItemDefaults.colors(indicatorColor = colors.surfaceContainerHighest)
                        else NavigationRailItemDefaults.colors()
                    )
                }
            }
        }
        // ペーパーは紙面と同じ地色に、右端の罫線1本で区切る
        DesignStyle.PAPER -> NavigationRail(
            modifier = modifier.railEndRule(colors.outlineVariant),
            containerColor = colors.surface
        ) { items(NavigationRailItemDefaults.colors()) }
        // クリアは白い(ダークは灰色の)横の帯に右端の細い区切り線。選んだタブは淡い青の丸と青いアイコン(ガラスのバーと同じ合図)
        DesignStyle.CLEAR -> NavigationRail(
            modifier = modifier.railEndRule(colors.outlineVariant),
            containerColor = colors.surfaceContainerLow
        ) {
            items(
                NavigationRailItemDefaults.colors(
                    selectedIconColor = colors.primary,
                    selectedTextColor = colors.onSurface,
                    indicatorColor = colors.primaryContainer,
                    unselectedIconColor = colors.onSurfaceVariant,
                    unselectedTextColor = colors.onSurface
                )
            )
        }
    }
}

/** タブのアイコン。ラベルを常に表示しているので、アイコン自体は読み上げない */
@Composable
private fun TopLevelIcon(destination: TopLevelDestination, selected: Boolean) {
    Icon(
        imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
        contentDescription = null
    )
}

@PreviewLightDark
@Composable
private fun EchoMarkNavigationBarPreview() {
    EchoMarkTheme {
        EchoMarkNavigationBar(selectedRoute = TopLevelDestination.BOOKMARKS.route, onNavigate = {})
    }
}
