package com.unchunks.echomark.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.graph.EdgeKind
import com.unchunks.echomark.domain.graph.GraphLayout
import com.unchunks.echomark.domain.graph.KnowledgeGraph
import com.unchunks.echomark.ui.insights.chartPalette
import kotlin.math.hypot

/** ズームの範囲 */
private const val MIN_SCALE = 0.6f
private const val MAX_SCALE = 6f

/** この倍率以上に拡大したら、全部の点にタイトルを出す(それまでは、つながりの多い点だけ) */
private const val LABEL_ALL_SCALE = 2.2f
private const val LABELED_NODES = 8

/** 選んだ点の相手のタイトルを図に出す倍率(それより小さいと、重なって読めないので、下の詳細の一覧で見せる) */
private const val LABEL_NEIGHBORS_SCALE = 1.8f

/**
 * ナレッジグラフの絵。ピンチで拡大・縮小、ドラッグで移動、点をタップで選ぶ。
 * 点の位置は [layout](0〜1)で、画面の大きさに合わせて広げる。選んだ点とそのつながりは濃く、ほかは薄くする。
 * 絵そのものは読み上げられないので、同じ内容を一覧でも見られるようにしてある(画面の「一覧」)。
 */
@Composable
fun GraphCanvas(
    graph: KnowledgeGraph,
    layout: GraphLayout,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    val palette = clusterPalette()
    val edgeColor = MaterialTheme.colorScheme.outline
    val strongEdgeColor = MaterialTheme.colorScheme.primary
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface)
    val ringColor = MaterialTheme.colorScheme.onSurface
    var scale by remember(layout) { mutableFloatStateOf(1f) }
    var offset by remember(layout) { mutableStateOf(Offset.Zero) }
    val selectedIndex = graph.nodes.indexOfFirst { it.id == selectedId }
    val neighborIndexes: Set<Int> = if (selectedIndex < 0) {
        emptySet()
    } else {
        graph.edgesOf(selectedIndex).map { if (it.a == selectedIndex) it.b else it.a }.toSet()
    }
    val mostConnected: Set<Int> = remember(graph) {
        graph.nodes.indices.sortedByDescending { graph.nodes[it].degree }.take(LABELED_NODES).toSet()
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clearAndSetSemantics {
                contentDescription = "ブックマークのつながりの図。${graph.nodes.size}件、つながり${graph.edges.size}本。" +
                    "「一覧」に切り替えると、同じ内容を読み上げで確かめられます"
            }
            .pointerInput(layout) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                    // 指の位置が動かないように、拡大の中心を保つ
                    val factor = newScale / scale
                    offset = (offset - (centroid - Offset(size.width / 2f, size.height / 2f))) * factor +
                        (centroid - Offset(size.width / 2f, size.height / 2f)) + pan
                    scale = newScale
                }
            }
            .pointerInput(layout, graph) {
                detectTapGestures { tap ->
                    val hit = nearestNode(tap, layout, size.width.toFloat(), size.height.toFloat(), scale, offset, 28.dp.toPx())
                    onSelect(hit?.let { graph.nodes[it].id })
                }
            }
    ) {
        if (layout.size != graph.nodes.size) return@Canvas
        fun position(i: Int) = project(layout.x[i], layout.y[i], size.width, size.height, scale, offset)
        val dimmed = selectedIndex >= 0

        graph.edges.forEach { edge ->
            val touchesSelection = edge.a == selectedIndex || edge.b == selectedIndex
            val alpha = if (!dimmed || touchesSelection) 0.85f else 0.15f
            val strong = edge.kind != EdgeKind.TAG
            drawLine(
                color = (if (strong) strongEdgeColor else edgeColor).copy(alpha = alpha),
                start = position(edge.a),
                end = position(edge.b),
                strokeWidth = (if (strong) 2f else 1f).dp.toPx(),
                pathEffect = if (strong) null else PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
            )
        }
        graph.nodes.forEachIndexed { i, node ->
            val highlighted = !dimmed || i == selectedIndex || i in neighborIndexes
            val center = position(i)
            val radius = (4f + minOf(node.degree, 10) * 0.5f).dp.toPx()
            drawCircle(palette(node.cluster).copy(alpha = if (highlighted) 1f else 0.25f), radius, center)
            if (i == selectedIndex) drawCircle(ringColor, radius + 3.dp.toPx(), center, style = Stroke(2.dp.toPx()))
        }
        graph.nodes.forEachIndexed { i, node ->
            val showLabel = i == selectedIndex || (i in neighborIndexes && scale >= LABEL_NEIGHBORS_SCALE) ||
                (!dimmed && (scale >= LABEL_ALL_SCALE || i in mostConnected))
            if (!showLabel) return@forEachIndexed
            val center = position(i)
            val label = measurer.measure(
                node.bookmark.title,
                labelStyle,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                constraints = Constraints(maxWidth = 120.dp.roundToPx())
            )
            drawText(label, topLeft = Offset(center.x - label.size.width / 2f, center.y + 10.dp.toPx()))
        }
    }
}

/** 点の色(つながりのまとまりごと。多いまとまりは 5 色、それ以外は 1 色)。 */
@Composable
private fun clusterPalette(): (Int) -> Color {
    val colors = chartPalette()
    val other = MaterialTheme.colorScheme.outline
    return { cluster -> if (cluster < colors.size - 1) colors[cluster] else other }
}

/** 0〜1 の位置を、画面の位置にする(中心を基準に [scale] 倍して [offset] だけずらす)。 */
internal fun project(x: Float, y: Float, width: Float, height: Float, scale: Float, offset: Offset): Offset =
    Offset(
        width / 2f + (x - 0.5f) * width * scale + offset.x,
        height / 2f + (y - 0.5f) * height * scale + offset.y
    )

/** タップした位置に一番近い点(画面上で [radiusPx] 以内)。無ければ null。 */
internal fun nearestNode(
    tap: Offset,
    layout: GraphLayout,
    width: Float,
    height: Float,
    scale: Float,
    offset: Offset,
    radiusPx: Float
): Int? {
    var best: Int? = null
    var bestDistance = radiusPx
    for (i in 0 until layout.size) {
        val p = project(layout.x[i], layout.y[i], width, height, scale, offset)
        val distance = hypot(p.x - tap.x, p.y - tap.y)
        if (distance <= bestDistance) {
            best = i
            bestDistance = distance
        }
    }
    return best
}
