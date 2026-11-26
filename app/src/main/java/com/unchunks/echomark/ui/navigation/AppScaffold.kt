package com.unchunks.echomark.ui.navigation

import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.repository.DesignStyle
import com.unchunks.echomark.ui.theme.EchoMarkDesign

/**
 * アプリ全体の枠。[navigation] に応じて、下にボトムバー([NavigationLayout.BAR])か左にナビゲーションレール
 * ([NavigationLayout.RAIL])を出す。[showNavigation] が false なら隠す(サブ画面のボトムバー・はじめにの案内)。
 *
 * インセット: 左右と下(ナビゲーションバー・ボトムバー)の分だけ余白を付けて consumeWindowInsets する。
 * 上(ステータスバー)は消費せず、各画面の EchoMarkTopAppBar(既定の windowInsets)が自分の背景色で受け持つ。
 * ここで上まで消費すると、ステータスバーの帯がこの Scaffold の背景のままになり、スクロールで色が変わる
 * TopAppBar とつながらない。各画面の imePadding は、ここで消費した下の分を差し引いた残りだけを足す。
 * レールを出しているときは、左(画面の始まり側)の分はレールが受け持つので、中身の側では余白を付けずに消費だけする
 * (インセットはウィンドウの端からの値なので、レールの右に置いた中身でもう一度余白を付けると二重になる)。
 *
 * @param content 画面本体。渡す Modifier(余白とインセットの消費)を付けて表示すること
 */
@Composable
internal fun EchoMarkAppScaffold(
    showNavigation: Boolean,
    selectedRoute: String?,
    onNavigate: (String) -> Unit,
    navigation: NavigationLayout = NavigationLayout.BAR,
    content: @Composable (Modifier) -> Unit
) {
    val showRail = navigation == NavigationLayout.RAIL && showNavigation
    Row(Modifier.fillMaxSize()) {
        if (navigation == NavigationLayout.RAIL) {
            AnimatedVisibility(
                visible = showNavigation,
                enter = expandHorizontally(tween(TRANSITION_MILLIS)) + fadeIn(tween(TRANSITION_MILLIS)),
                exit = shrinkHorizontally(tween(TRANSITION_MILLIS)) + fadeOut(tween(TRANSITION_MILLIS))
            ) {
                EchoMarkNavigationRail(
                    selectedRoute = selectedRoute,
                    onNavigate = onNavigate,
                    modifier = Modifier.fillMaxHeight()
                )
            }
        }
        AppContentScaffold(
            showNavigation = showNavigation,
            showRail = showRail,
            navigation = navigation,
            selectedRoute = selectedRoute,
            onNavigate = onNavigate,
            modifier = Modifier.weight(1f),
            content = content
        )
    }
}

/**
 * [EchoMarkAppScaffold] のうち、レールの右側(中身とボトムバー)。
 * クリア(iPhone 風)のボトムバーは Scaffold の bottomBar に置かず、中身の上に浮かせたガラスのバー(リキッドグラス)にする。
 * 中身がバーの裏まで続き、ぼけて透けて見える。覆う高さは [LocalBottomBarOverlap] で各画面に伝える。
 */
@Composable
private fun AppContentScaffold(
    showNavigation: Boolean,
    showRail: Boolean,
    navigation: NavigationLayout,
    selectedRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit
) {
    val railInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Start)
    val glass = navigation == NavigationLayout.BAR && EchoMarkDesign.style == DesignStyle.CLEAR
    val backdrop = rememberGraphicsLayer()
    var backdropOrigin by remember { mutableStateOf(Offset.Zero) }
    var barHeightPx by remember { mutableIntStateOf(0) }
    val overlap = if (glass && showNavigation) with(LocalDensity.current) { barHeightPx.toDp() } else 0.dp
    Box(modifier) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .then(if (showRail) Modifier.consumeWindowInsets(railInsets) else Modifier),
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(
                if (showRail) WindowInsetsSides.End + WindowInsetsSides.Bottom else WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
            ),
            bottomBar = {
                if (navigation == NavigationLayout.BAR && !glass) {
                    AnimatedVisibility(
                        visible = showNavigation,
                        enter = slideInVertically(tween(TRANSITION_MILLIS)) { it } + fadeIn(tween(TRANSITION_MILLIS)),
                        exit = slideOutVertically(tween(TRANSITION_MILLIS)) { it } + fadeOut(tween(TRANSITION_MILLIS))
                    ) {
                        EchoMarkNavigationBar(selectedRoute = selectedRoute, onNavigate = onNavigate)
                    }
                }
            }
        ) { innerPadding ->
            CompositionLocalProvider(LocalBottomBarOverlap provides overlap) {
                content(
                    Modifier
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding)
                        .then(if (glass) Modifier.recordBackdrop(backdrop) { backdropOrigin = it } else Modifier)
                )
            }
        }
        if (glass) {
            AnimatedVisibility(
                visible = showNavigation,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(tween(TRANSITION_MILLIS)) { it } + fadeIn(tween(TRANSITION_MILLIS)),
                exit = slideOutVertically(tween(TRANSITION_MILLIS)) { it } + fadeOut(tween(TRANSITION_MILLIS))
            ) {
                GlassNavigationBar(
                    selectedRoute = selectedRoute,
                    onNavigate = onNavigate,
                    backdrop = backdrop,
                    backdropOrigin = { backdropOrigin },
                    onOverlapChanged = { barHeightPx = it }
                )
            }
        }
    }
}

/** ボトムバーのある画面の Scaffold の contentWindowInsets。重ねたボトムバーに覆われる分([LocalBottomBarOverlap])を下に足す */
@Composable
fun bottomBarAwareContentInsets(): WindowInsets =
    ScaffoldDefaults.contentWindowInsets.add(WindowInsets(bottom = LocalBottomBarOverlap.current))

/**
 * この要素が描く中身を [layer] にも記録する([GlassNavigationBar] が裏の中身として、ぼかして描き直す)。
 * [onOrigin] には、記録した中身の左上の位置(画面の左上から)を渡す。
 */
private fun Modifier.recordBackdrop(layer: GraphicsLayer, onOrigin: (Offset) -> Unit): Modifier = this
    .onGloballyPositioned { onOrigin(it.positionInRoot()) }
    .drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }
