package com.unchunks.echomark.ui.navigation

import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.separatingVerticalHingeBounds
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.rememberHiltViewModelFactory
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.ViewModel
import androidx.lifecycle.defaultViewModelCreationExtras
import androidx.lifecycle.defaultViewModelProviderFactory
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass

/** トップレベル画面(タブ)の切り替えの出し方 */
internal enum class NavigationLayout {
    /** 画面下のナビゲーションバー(幅が 600dp 未満。スマートフォンの縦向きなど) */
    BAR,

    /** 画面左のナビゲーションレール(幅が 600dp 以上。タブレット・折りたたみを開いたとき・スマートフォンの横向き) */
    RAIL
}

/**
 * 画面の広さに合わせたアプリ全体の配置。
 *
 * - タブの切り替え: 幅 600dp 以上(Material の Medium 以上)はナビゲーションレール、それ未満はボトムバー
 * - 一覧と詳細を左右に並べる 2 画面表示: 幅 840dp 以上(Expanded 以上)かつ高さ 480dp 以上のとき。
 *   600〜840dp(縦向きのタブレットなど)は 2 つ並べると両方が窮屈になるので 1 画面のまま(一覧のカードを複数列にする)。
 *   スマートフォンの横向きは幅が足りても高さが低く、左右それぞれが窮屈になるので 1 画面にする。
 *   ただし折りたたみを本のように半分開いて、縦の折り目が画面を分けているときは、幅によらず折り目の左右に分けて並べる
 *   (1 画面のままだと中身が折り目にかかる)
 *
 * @property paneDirective 一覧・詳細の並べ方([ListDetailPanes] に渡す)。折り目の位置(避ける範囲)も含む
 */
@Immutable
internal class AdaptiveLayout(
    val navigation: NavigationLayout,
    val paneDirective: PaneScaffoldDirective
) {
    /** 一覧と詳細を左右に並べるか */
    val isTwoPane: Boolean get() = paneDirective.maxHorizontalPartitions >= 2
}

/** 今のウィンドウの大きさ・折りたたみの状態から [AdaptiveLayout] を求める。大きさや姿勢が変わると作り直す */
@Composable
internal fun currentAdaptiveLayout(): AdaptiveLayout {
    val info = currentWindowAdaptiveInfoV2()
    return remember(info) { adaptiveLayoutOf(info) }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
internal fun adaptiveLayoutOf(info: WindowAdaptiveInfo): AdaptiveLayout {
    val sizeClass = info.windowSizeClass
    val navigation = if (sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)) {
        NavigationLayout.RAIL
    } else {
        NavigationLayout.BAR
    }
    val base = calculatePaneScaffoldDirective(info)
    val splitByHinge = navigation == NavigationLayout.RAIL && info.windowPosture.separatingVerticalHingeBounds.isNotEmpty()
    val roomForTwoPanes = sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) &&
        sizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
    return AdaptiveLayout(
        navigation = navigation,
        paneDirective = base.copy(
            // 一覧と詳細の 2 つまで(とても広い画面でも 3 つ目のペインは使わない)
            maxHorizontalPartitions = if (splitByHinge || roomForTwoPanes) 2 else 1,
            // ペインの間は余白ではなく区切り線で分ける([ListDetailPanes])。折り目があるときは折り目の幅だけ空く
            horizontalPartitionSpacerSize = 0.dp
        )
    )
}

/** 一覧と詳細を左右に並べるときの値(詳細 = primary、一覧 = secondary) */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private val TwoPaneValue = ThreePaneScaffoldValue(
    primary = PaneAdaptedValue.Expanded,
    secondary = PaneAdaptedValue.Expanded,
    tertiary = PaneAdaptedValue.Hidden
)

/** 一覧だけを出すときの値 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private val ListOnlyValue = ThreePaneScaffoldValue(
    primary = PaneAdaptedValue.Hidden,
    secondary = PaneAdaptedValue.Expanded,
    tertiary = PaneAdaptedValue.Hidden
)

/**
 * 一覧と詳細。[directive] が 2 画面(maxHorizontalPartitions >= 2)なら左に一覧・右に詳細を並べ、
 * そうでなければ一覧だけを出す(1 画面のときの詳細は、これまでどおりルートで開く)。
 *
 * 1 画面でも 2 画面でも一覧を同じ場所で描くので、折りたたみの開閉や回転で一覧のスクロール位置などが保たれる。
 * 縦の折り目が画面を分けているときは、折り目を避けて左右に振り分ける(directive の excludedBounds)。
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun ListDetailPanes(
    directive: PaneScaffoldDirective,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    ListDetailPaneScaffold(
        directive = directive,
        value = if (directive.maxHorizontalPartitions >= 2) TwoPaneValue else ListOnlyValue,
        listPane = { AnimatedPane { list() } },
        detailPane = {
            AnimatedPane {
                Row(Modifier.fillMaxSize()) {
                    VerticalDivider()
                    Box(Modifier.weight(1f)) { detail() }
                }
            }
        },
        modifier = modifier
    )
}

/**
 * 2 画面表示の詳細ペインに出す画面の ViewModel。
 *
 * 詳細・会話の ViewModel はルートの引数(bookmarkId・conversationId)を SavedStateHandle から読む。ペインには
 * ルートが無いので、[args] をルートの引数の代わりに SavedStateHandle の初期値として渡し、[key] ごとに別のインスタンスにする
 * (選んだブックマーク・会話ごとに ViewModel を分け、前に選んでいたものの状態や Snackbar の取り消しが混ざらないようにする)。
 * ストアは今の画面(一覧の NavBackStackEntry)なので、回転やプロセスの終了後も同じ [key] なら SavedStateHandle ごと戻る。
 * そのぶん、選び直した前の ViewModel は一覧の画面を閉じるまで残る(購読は WhileSubscribed なので、見ていない間は止まる)。
 */
@Composable
internal inline fun <reified VM : ViewModel> paneViewModel(key: String, args: Bundle): VM {
    val owner = checkNotNull(LocalViewModelStoreOwner.current) {
        "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
    }
    val factory = rememberHiltViewModelFactory(owner.defaultViewModelProviderFactory)
    val extras = MutableCreationExtras(owner.defaultViewModelCreationExtras).apply { set(DEFAULT_ARGS_KEY, args) }
    return viewModel(viewModelStoreOwner = owner, key = key, factory = factory, extras = extras)
}

/** 広い画面で、本文やメッセージを読みやすい幅に収めるときの最大幅 */
internal val WideContentMaxWidth: Dp = 760.dp

/** 幅いっぱいに置いたうえで、中身を中央の [maxWidth] までに収める(広い画面で1行が長くなりすぎないように) */
internal fun Modifier.centeredMaxWidth(maxWidth: Dp = WideContentMaxWidth): Modifier =
    this
        .fillMaxWidth()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = maxWidth)
        .fillMaxWidth()
