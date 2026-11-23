package com.unchunks.echomark.ui.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.ui.navigation.bottomBarAwareContentInsets
import com.unchunks.echomark.ui.navigation.centeredMaxWidth
import com.unchunks.echomark.ui.settings.japaneseParagraph
import com.unchunks.echomark.ui.theme.EchoMarkTheme

/**
 * 「見つける」タブの行き先。保存したものを読み返す・調べる・整える画面を開く。
 * 既定は何もしない(プレビュー・スクリーンショット用)。
 */
class ExploreActions(
    val onOpenReview: () -> Unit = {},
    val onOpenDiscover: () -> Unit = {},
    val onOpenInsights: () -> Unit = {},
    val onOpenCollections: () -> Unit = {},
    val onOpenTags: () -> Unit = {},
    val onOpenDuplicates: () -> Unit = {},
    val onOpenBrokenLinks: () -> Unit = {},
    val onOpenPodcasts: () -> Unit = {}
)

/**
 * 「見つける」タブ。一覧のメニューの奥にあった画面(読む・ふり返る、資料を探す、分析、ポッドキャスト、コレクション、タグ、重複、リンク切れ)を
 * ひと目で見渡せるように並べる。状態を持たないので、スクリーンショットテストでそのまま描画できる。
 *
 * 上に、いちばんよく使う「読む・ふり返る」を大きく置き、残りは幅に合わせて 1〜3 列のタイルにする。
 * 同じ行のタイルは、説明の行数が違っても高さをそろえる。
 *
 * インセット: 親の Scaffold(EchoMarkAppScaffold)が左右と下の分を付けて消費済み。上は TopAppBar が受け持つ。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(actions: ExploreActions, modifier: Modifier = Modifier) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        // クリアのガラスのボトムバーは中身の上に重なるので、その分を下に空ける(追加ボタン・スナックバーもバーの上に出す)
        contentWindowInsets = bottomBarAwareContentInsets(),
        topBar = {
            TopAppBar(
                title = { Text("見つける", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)
                // 広い画面では中央の読みやすい幅に収める(スクロールは幅全体で受ける)
                .centeredMaxWidth(EXPLORE_MAX_WIDTH),
            verticalArrangement = Arrangement.spacedBy(TILE_SPACING)
        ) {
            FeaturedCard(
                icon = Icons.Outlined.AutoStories,
                title = "読む・ふり返る",
                description = "あとで読む・今週のふり返り・復習。保存したものを読み返して、身につけます",
                onClick = actions.onOpenReview
            )
            TileGrid(tiles = exploreTiles(actions))
        }
    }
}

/** タイル1つ分の中身 */
private class ExploreTile(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val tone: TileTone,
    val onClick: () -> Unit
)

/** タイルのアイコンの色合い。色で「集める・知る・整える」のまとまりを分ける(意味は見出しと説明でも伝わる) */
private enum class TileTone { DISCOVER, INSIGHT, ORGANIZE }

private fun exploreTiles(actions: ExploreActions): List<ExploreTile> = listOf(
    ExploreTile(
        Icons.Outlined.TravelExplore, "資料を探す", "テーマに合う記事や動画を、AI が Web から選びます",
        TileTone.DISCOVER, actions.onOpenDiscover
    ),
    ExploreTile(
        Icons.Outlined.Insights, "ライブラリの分析", "タグ・統計・関心の変化・つながりの図・地図",
        TileTone.INSIGHT, actions.onOpenInsights
    ),
    ExploreTile(
        Icons.Outlined.Podcasts, "ポッドキャスト", "保存したものを、端末の読み上げで聞けるエピソードにします",
        TileTone.DISCOVER, actions.onOpenPodcasts
    ),
    ExploreTile(
        Icons.Outlined.Folder, "コレクション", "ブックマークを集めて、名前を付けてまとめます",
        TileTone.ORGANIZE, actions.onOpenCollections
    ),
    ExploreTile(
        Icons.AutoMirrored.Outlined.Label, "タグの管理", "名前の変更・統合・表記ゆれの整理",
        TileTone.ORGANIZE, actions.onOpenTags
    ),
    ExploreTile(
        Icons.Outlined.ContentCopy, "重複と関連", "同じものや似たものを見つけて、まとめます",
        TileTone.ORGANIZE, actions.onOpenDuplicates
    ),
    ExploreTile(
        Icons.Outlined.LinkOff, "リンク切れ", "開けなくなったリンクを確かめます",
        TileTone.ORGANIZE, actions.onOpenBrokenLinks
    )
)

/** いちばん上に大きく出すカード(読む・ふり返る)。ブランドの色で塗り、ほかのタイルより目立たせる。 */
@Composable
private fun FeaturedCard(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { role = Role.Button }
    ) {
        val badge: @Composable () -> Unit = {
            IconBadge(
                icon = icon,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                size = 56.dp,
                iconSize = 28.dp
            )
        }
        val texts: @Composable () -> Unit = {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium.japaneseParagraph())
        }
        if (LocalDensity.current.fontScale >= STACKED_FONT_SCALE) {
            // 文字を大きくしているときは、アイコンを上に置いて文字に幅をすべて使う(見出しが途中で折り返さないように)
            Column(Modifier.padding(20.dp)) {
                badge()
                Spacer(Modifier.height(12.dp))
                texts()
            }
        } else {
            Row(
                modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 20.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                badge()
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) { texts() }
                Spacer(Modifier.width(8.dp))
                // 装飾(行全体がボタン)
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null)
            }
        }
    }
}

/**
 * タイルを幅に合わせた列数で並べる。行ごとに高さをそろえる(Lazy のグリッドは行内の高さがそろわないため、自前で並べる)。
 * 文字サイズを大きくしているときは、1列あたりの幅を広く見積もって列を減らし、タイトルが途中で切れないようにする。
 */
@Composable
private fun TileGrid(tiles: List<ExploreTile>) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val minTileWidth = TILE_MIN_WIDTH * fontScale.coerceAtLeast(1f)
        val columns = ((maxWidth + TILE_SPACING) / (minTileWidth + TILE_SPACING)).toInt().coerceIn(1, MAX_COLUMNS)
        Column(verticalArrangement = Arrangement.spacedBy(TILE_SPACING)) {
            tiles.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(TILE_SPACING)
                ) {
                    row.forEach { tile ->
                        TileCard(
                            tile = tile,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                    // 最後の行がタイルで埋まらないときは、残りを空けて幅をそろえる
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun TileCard(tile: ExploreTile, modifier: Modifier = Modifier) {
    val (badgeContainer, badgeContent) = tile.tone.colors()
    Card(
        onClick = tile.onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.semantics { role = Role.Button }
    ) {
        Column(Modifier.padding(16.dp)) {
            IconBadge(icon = tile.icon, containerColor = badgeContainer, contentColor = badgeContent)
            Spacer(Modifier.height(12.dp))
            Text(
                tile.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                tile.description,
                style = MaterialTheme.typography.bodySmall.japaneseParagraph(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TileTone.colors(): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        TileTone.DISCOVER -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        TileTone.INSIGHT -> scheme.primaryContainer to scheme.onPrimaryContainer
        TileTone.ORGANIZE -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
}

/** 丸い背景つきのアイコン。装飾なので読み上げない(意味は見出しで伝える)。 */
@Composable
private fun IconBadge(
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .background(containerColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(iconSize))
    }
}

private val TILE_SPACING = 12.dp

/** タイル1列の最小幅。スマートフォンの縦向きで 2 列になる(余白を引いた幅の半分より少し小さい) */
private val TILE_MIN_WIDTH = 156.dp

/** 列の上限。タイルは 6 つなので、広い画面でも 3 列までにして、最後の行に空きを作らない */
private const val MAX_COLUMNS = 3

/** 広い画面でのタイル全体の最大幅(3 列のタイルが間延びしないくらい) */
private val EXPLORE_MAX_WIDTH = 920.dp

/** 大きなカードを縦に積む文字サイズ(端末の文字サイズの設定がこれ以上のとき) */
private const val STACKED_FONT_SCALE = 1.5f

@PreviewLightDark
@Composable
private fun ExploreScreenPreview() {
    EchoMarkTheme {
        Surface { ExploreScreen(actions = ExploreActions()) }
    }
}
