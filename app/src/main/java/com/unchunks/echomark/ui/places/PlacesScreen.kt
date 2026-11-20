package com.unchunks.echomark.ui.places

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.components.composeui.ReadableLazyColumn
import com.unchunks.echomark.domain.place.GeoPoint
import com.unchunks.echomark.domain.place.MapFrame
import com.unchunks.echomark.domain.place.PlaceGroup
import com.unchunks.echomark.domain.place.graticuleLines
import com.unchunks.echomark.domain.place.graticuleStep
import com.unchunks.echomark.domain.repository.PlaceDetection
import com.unchunks.echomark.ui.components.EmptyState
import com.unchunks.echomark.ui.components.LoadingState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 場所のあるブックマークの分布図と一覧。ViewModel をつなぐだけの薄いラッパー。
 * @param onOpenBookmark ブックマークをタップしたとき(詳細画面を開く)
 */
@Composable
fun PlacesScreen(
    onBack: () -> Unit,
    onOpenBookmark: (Long) -> Unit,
    viewModel: PlacesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    PlacesContent(
        uiState = uiState,
        onBack = onBack,
        onOpenBookmark = onOpenBookmark,
        onSelect = viewModel::select,
        onDetect = viewModel::detect,
        onOpenInMap = { point, title -> openInMapApp(context, point, title) },
        onMapBackgroundChange = viewModel::setMapBackground
    )
}

/** 画面の本体(状態を受け取って描くだけ)。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesContent(
    uiState: PlacesUiState,
    onBack: () -> Unit,
    onOpenBookmark: (Long) -> Unit,
    onSelect: (String?) -> Unit,
    onDetect: () -> Unit,
    onOpenInMap: (GeoPoint, String?) -> Unit,
    modifier: Modifier = Modifier,
    onMapBackgroundChange: (Boolean) -> Unit = {},
    isOnline: Boolean = rememberIsOnline(),
    tileMap: TileMapSlot = DefaultTileMap
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("場所", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { innerPadding ->
        when {
            uiState.isLoading -> LoadingState(modifier = Modifier.padding(innerPadding))
            else -> Column(Modifier.padding(innerPadding)) {
                // 地図は一覧の外(上)に固定する。タイルの地図は指で動かすので、スクロールする一覧の中に置くと操作が取り合いになる
                if (uiState.groups.isNotEmpty()) {
                    MapArea(uiState, onSelect, isOnline, tileMap)
                }
                ReadableLazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    if (uiState.groups.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(
                                icon = Icons.Outlined.Place,
                                title = "場所のあるブックマークがありません",
                                description = "詳細画面で場所を入れるか、下の「場所を探す」で、地図のリンクや写真の位置情報から読み取れます。",
                                modifier = Modifier.height(240.dp)
                            )
                        }
                    } else {
                        item(key = "summary") {
                            Text(
                                buildString {
                                    append("座標のあるブックマーク ${uiState.locatedCount}件")
                                    if (uiState.nameOnlyCount > 0) append("・名前だけのもの ${uiState.nameOnlyCount}件")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                            )
                        }
                        item(key = "map-background") {
                            MapBackgroundSwitch(uiState.mapBackgroundEnabled, onMapBackgroundChange)
                        }
                    }
                    item(key = "detect") {
                        DetectSection(uiState, onDetect)
                    }
                    items(uiState.groups, key = { it.key }) { group ->
                        PlaceGroupCard(
                            group = group,
                            selected = group.key == uiState.selectedKey,
                            onToggle = { onSelect(if (group.key == uiState.selectedKey) null else group.key) },
                            onOpenBookmark = onOpenBookmark,
                            onOpenInMap = onOpenInMap
                        )
                    }
                }
            }
        }
    }
}

/** タイルの地図を描く部分。テストでは、地図のライブラリを使わない Fake に差し替える。 */
typealias TileMapSlot = @Composable (
    groups: List<PlaceGroup>,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
    onFailed: (String) -> Unit,
    modifier: Modifier
) -> Unit

/** 実際のタイルの地図([PlaceTileMap])。 */
val DefaultTileMap: TileMapSlot = { groups, selectedKey, onSelect, onFailed, modifier ->
    PlaceTileMap(groups, selectedKey, onSelect, onFailed, modifier)
}

/** 地図の高さ */
private val MAP_HEIGHT = 260.dp

/**
 * 地図の部分。「地図の背景」がオンで、ネットワークがあり、読み込みに失敗していなければタイルの地図を出す。
 * オフのとき・オフラインのとき・読み込みに失敗したときは、分布図(タイルの無い図)を出し、理由を一言添える
 * (オフラインから戻ったら自動でもう一度試し、失敗したときは「もう一度試す」を出す)。
 */
@Composable
private fun MapArea(uiState: PlacesUiState, onSelect: (String?) -> Unit, isOnline: Boolean, tileMap: TileMapSlot) {
    var failure by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    // スイッチを入れ直したとき・ネットワークが戻ったときは、失敗の記録を外してもう一度試す
    LaunchedEffect(uiState.mapBackgroundEnabled, isOnline, uiState.offlineMode) { failure = null }
    // オフラインモードの間は地図を作らない(タイルを読みに行かない。通信そのものは共通の OkHttpClient でも断る)
    val useTiles = uiState.mapBackgroundEnabled && isOnline && !uiState.offlineMode && failure == null

    Column {
        if (useTiles) {
            // 試し直すたびに作り直す(前の地図は組みから外れて、通信も止まる)
            key(attempt) {
                tileMap(
                    uiState.groups, uiState.selectedKey, onSelect, { failure = it },
                    Modifier.fillMaxWidth().height(MAP_HEIGHT).padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        } else {
            PlaceMap(
                groups = uiState.groups,
                selectedKey = uiState.selectedKey,
                onSelect = onSelect,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        if (uiState.mapBackgroundEnabled && !useTiles) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        uiState.offlineMode -> "オフラインモードのため、地図の背景は出しません。分布図を表示しています。"
                        !isOnline -> "オフラインのため、地図の背景は出せません。分布図を表示しています。"
                        else -> "地図の背景を読み込めませんでした。分布図を表示しています。"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isOnline && !uiState.offlineMode) {
                    TextButton(onClick = { failure = null; attempt++ }) { Text("もう一度試す") }
                }
            }
        }
    }
}

/** 「地図の背景を表示」のスイッチ(既定はオフ)と、通信の説明。 */
@Composable
private fun MapBackgroundSwitch(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("地図の背景を表示", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(checked = enabled, onCheckedChange = onChange)
        }
        Text(
            "オンにすると、この画面を開いている間だけ、見ている範囲の地図(OpenFreeMap のタイル)をネットワークから読み込みます。" +
                "送るのは、見ている範囲のタイルの番号・IP アドレス・アプリ名だけで、ブックマークの内容や場所は送りません。" +
                "オフラインのときや読み込めないときは、分布図を表示します。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!enabled) {
            Text(
                "いまは地図の画像を使わず、位置の分布だけを描いています(北が上)。実際の地図は「地図アプリで開く」で見られます。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DetectSection(uiState: PlacesUiState, onDetect: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("場所を探す", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Text(
            "座標のまだ無いリンクと写真から、場所を読み取ります。リンクは地図のリンクに入っている座標だけを見ます" +
                "(通信はしません)。写真は、保存した写真の位置情報(撮影場所)を読みます。押したときだけ調べ、現在地は使いません。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = onDetect, enabled = !uiState.detecting) { Text("場所を探す") }
            if (uiState.detecting) CircularProgressIndicator(modifier = Modifier.height(24.dp))
        }
        uiState.lastDetection?.let { Text(it.summaryText(), style = MaterialTheme.typography.bodyMedium) }
        uiState.errorMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    }
}

/** 「場所を探す」の結果の一文。 */
internal fun PlaceDetection.summaryText(): String = when {
    checked == 0 -> "調べるリンク・写真がありません(すべて場所が入っているか、リンクと写真がありません)"
    found == 0 -> "${checked}件を調べましたが、場所は見つかりませんでした"
    else -> "${checked}件を調べて、${found}件の場所を入れました(リンク ${fromLinks}件・写真 ${fromPhotos}件)"
}

@Composable
private fun PlaceGroupCard(
    group: PlaceGroup,
    selected: Boolean,
    onToggle: () -> Unit,
    onOpenBookmark: (Long) -> Unit,
    onOpenInMap: (GeoPoint, String?) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        onClick = onToggle
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(group.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${group.bookmarks.size}件" + (group.center?.let { "・${it.label()}" } ?: "・座標なし"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (selected) {
                group.bookmarks.forEach { bookmark ->
                    Text(
                        bookmark.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClickLabel = "詳細を開く") { onOpenBookmark(bookmark.id) }
                            .padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                group.center?.let { center ->
                    OutlinedButton(onClick = { onOpenInMap(center, group.label) }) { Text("地図アプリで開く") }
                }
            }
        }
    }
}

private const val DOT_HIT_DP = 28

/**
 * 場所の分布図。緯度・経度の目盛り線の上に、まとまりごとの点を置く(タイルの地図の画像は使わない)。
 * 点をタップすると、そのまとまりを選ぶ。絵は読み上げられないので、同じ内容を下の一覧で見られる。
 */
@Composable
private fun PlaceMap(groups: List<PlaceGroup>, selectedKey: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    val placed = groups.filter { it.center != null }
    val measurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val dotColor = MaterialTheme.colorScheme.primary
    val selectedColor = MaterialTheme.colorScheme.tertiary
    val ringColor = MaterialTheme.colorScheme.onSurface
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val background = MaterialTheme.colorScheme.surfaceContainerLow
    if (placed.isEmpty()) return
    val points = placed.mapNotNull { it.center }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp)
            .clearAndSetSemantics { contentDescription = "場所のあるブックマークの分布図。${placed.size}か所。下の一覧でも同じ内容を見られます" }
            .pointerInput(groups) {
                detectTapGestures { tap ->
                    val frame = MapFrame.fit(points, size.width.toFloat(), size.height.toFloat(), 24.dp.toPx())
                    val hit = placed.minByOrNull { group ->
                        val (x, y) = frame.toScreen(group.center!!)
                        hypot(x - tap.x, y - tap.y)
                    }?.takeIf { group ->
                        val (x, y) = frame.toScreen(group.center!!)
                        hypot(x - tap.x, y - tap.y) <= DOT_HIT_DP.dp.toPx()
                    }
                    onSelect(hit?.key)
                }
            }
    ) {
        drawRect(background)
        val frame = MapFrame.fit(points, size.width, size.height, 24.dp.toPx())
        val bounds = frame.visibleBounds()
        val step = graticuleStep(max(bounds.maxLat - bounds.minLat, bounds.maxLng - bounds.minLng))
        graticuleLines(bounds.minLat, bounds.maxLat, step).forEach { lat ->
            val y = frame.toScreen(GeoPoint(lat, bounds.minLng)).second
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            drawText(measurer.measure(degreeLabel(lat, "N", "S"), axisStyle), topLeft = Offset(4.dp.toPx(), y - 14.dp.toPx()))
        }
        graticuleLines(bounds.minLng, bounds.maxLng, step).forEach { lng ->
            val x = frame.toScreen(GeoPoint(bounds.minLat, lng)).first
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            drawText(
                measurer.measure(degreeLabel(lng, "E", "W"), axisStyle),
                topLeft = Offset(x + 3.dp.toPx(), size.height - 16.dp.toPx())
            )
        }
        placed.forEach { group ->
            val (x, y) = frame.toScreen(group.center!!)
            val radius = (6f + sqrt(group.bookmarks.size.toFloat()) * 2f).dp.toPx()
            val isSelected = group.key == selectedKey
            drawCircle((if (isSelected) selectedColor else dotColor).copy(alpha = 0.85f), radius, Offset(x, y))
            if (isSelected) {
                drawCircle(ringColor, radius + 3.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx()))
            }
        }
    }
}

/** 目盛りの文字(例: 「35°N」「139.5°E」)。 */
internal fun degreeLabel(value: Double, positive: String, negative: String): String {
    val abs = abs(value)
    val text = if (abs == abs.toLong().toDouble()) abs.toLong().toString() else String.format(Locale.ROOT, "%.3f", abs).trimEnd('0').trimEnd('.')
    return "$text°${if (value >= 0) positive else negative}"
}
