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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
        onOpenInMap = { point, title -> openInMapApp(context, point, title) }
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
    modifier: Modifier = Modifier
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
            else -> ReadableLazyColumn(
                modifier = Modifier.padding(innerPadding),
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
                    item(key = "map") {
                        PlaceMap(
                            groups = uiState.groups,
                            selectedKey = uiState.selectedKey,
                            onSelect = onSelect,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
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
                        Text(
                            "地図の画像は使わず、位置の分布だけを描いています。実際の地図は「地図アプリで開く」で見られます(北が上)。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                        )
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
