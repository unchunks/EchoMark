package com.unchunks.echomark.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max

/*
 * ライブラリの分析で使うグラフ。外部のグラフ用ライブラリは使わず、Compose の Canvas で描く。
 * どのグラフも色だけに頼らない: 系列は凡例(名前つき)で示し、数値は隣の表にも出す。
 * 読み上げ用に、グラフ全体を contentDescription の 1 文にまとめる(個々の棒・点は読み上げない)。
 */

/** グラフの系列1つ(名前・色・値)。値は X 軸の項目と同じ並び。 */
data class ChartSeries(val name: String, val color: Color, val values: List<Int>)

/** 系列の見分けがつくよう、テーマの色から選んだ順番(最大 5 本)。 */
@Composable
fun chartPalette(): List<Color> {
    val colors = MaterialTheme.colorScheme
    return listOf(colors.primary, colors.tertiary, colors.error, colors.secondary, colors.outline)
}

/** 系列の名前と色の見本を並べた凡例。 */
@Composable
fun ChartLegend(series: List<ChartSeries>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        series.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(10.dp)) { drawRoundRect(s.color, cornerRadius = CornerRadius(2.dp.toPx())) }
                Spacer(Modifier.width(6.dp))
                Text(s.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Y 軸の目盛りの最大値(1, 2, 5 の倍数で切りのよい値に切り上げる)。 */
internal fun niceMax(value: Int): Int {
    if (value <= 4) return 4
    var step = 1
    while (true) {
        for (unit in intArrayOf(1, 2, 5)) {
            val candidate = unit * step
            if (value <= candidate * 4) return candidate * 4
        }
        step *= 10
    }
}

/**
 * 縦棒グラフ(系列が複数なら、項目ごとに横に並べる)。
 * @param labels X 軸の項目名(月など)。狭いと間引いて出す
 * @param summary 読み上げ用の説明
 */
@Composable
fun BarChart(
    labels: List<String>,
    series: List<ChartSeries>,
    summary: String,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp
) {
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val top = niceMax(series.maxOfOrNull { s -> s.values.maxOrNull() ?: 0 } ?: 0)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { contentDescription = summary }
    ) {
        val axisWidth = axisLabelWidth(measurer, axisStyle, top)
        val bottomLabelHeight = 18.dp.toPx()
        val chartLeft = axisWidth
        val chartHeight = size.height - bottomLabelHeight
        val chartWidth = size.width - chartLeft
        drawGrid(measurer, axisStyle, gridColor, top, chartLeft, chartHeight, chartWidth)
        if (labels.isEmpty() || series.isEmpty()) return@Canvas
        val slot = chartWidth / labels.size
        val groupWidth = slot * 0.7f
        val barWidth = groupWidth / series.size
        val radius = CornerRadius(2.dp.toPx())
        labels.indices.forEach { i ->
            series.forEachIndexed { j, s ->
                val value = s.values.getOrElse(i) { 0 }
                if (value > 0) {
                    val barHeight = chartHeight * value / top
                    drawRoundRect(
                        color = s.color,
                        topLeft = Offset(chartLeft + slot * i + (slot - groupWidth) / 2 + barWidth * j, chartHeight - barHeight),
                        size = Size(max(barWidth - 1.dp.toPx(), 1f), barHeight),
                        cornerRadius = radius
                    )
                }
            }
        }
        drawXLabels(measurer, axisStyle, labels, chartLeft, slot, chartHeight + 2.dp.toPx())
    }
}

/**
 * 折れ線グラフ(月ごとの件数の移り変わり)。
 * @param labels X 軸の項目名(古い順)
 */
@Composable
fun LineChart(
    labels: List<String>,
    series: List<ChartSeries>,
    summary: String,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp
) {
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val top = niceMax(series.maxOfOrNull { s -> s.values.maxOrNull() ?: 0 } ?: 0)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { contentDescription = summary }
    ) {
        val axisWidth = axisLabelWidth(measurer, axisStyle, top)
        val bottomLabelHeight = 18.dp.toPx()
        val chartLeft = axisWidth
        val chartHeight = size.height - bottomLabelHeight
        val chartWidth = size.width - chartLeft
        drawGrid(measurer, axisStyle, gridColor, top, chartLeft, chartHeight, chartWidth)
        if (labels.isEmpty()) return@Canvas
        val slot = chartWidth / labels.size
        fun x(i: Int) = chartLeft + slot * i + slot / 2
        fun y(value: Int) = chartHeight - chartHeight * value / top
        series.forEach { s ->
            val path = Path()
            s.values.forEachIndexed { i, value ->
                if (i == 0) path.moveTo(x(i), y(value)) else path.lineTo(x(i), y(value))
            }
            drawPath(path, s.color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            s.values.forEachIndexed { i, value -> drawCircle(s.color, radius = 3.dp.toPx(), center = Offset(x(i), y(value))) }
        }
        drawXLabels(measurer, axisStyle, labels, chartLeft, slot, chartHeight + 2.dp.toPx())
    }
}

/** 横棒の一覧(名前・棒・件数)。最大値を 100% として棒の長さを決める。 */
@Composable
fun HorizontalBars(
    entries: List<Pair<String, Int>>,
    color: Color,
    modifier: Modifier = Modifier
) {
    val maxValue = max(entries.maxOfOrNull { it.second } ?: 0, 1)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.forEach { (label, count) ->
            Row(
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$label、${count}件" },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label,
                    modifier = Modifier.width(88.dp),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Canvas(Modifier.weight(1f).height(14.dp).padding(horizontal = 8.dp)) {
                    val length = size.width * count / maxValue
                    drawRoundRect(color, size = Size(max(length, 2.dp.toPx()), size.height), cornerRadius = CornerRadius(3.dp.toPx()))
                }
                Text(
                    "$count",
                    modifier = Modifier.width(40.dp),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }
        }
    }
}

private fun DrawScope.axisLabelWidth(
    measurer: TextMeasurer,
    style: TextStyle,
    top: Int
): Float = measurer.measure(top.toString(), style).size.width + 8.dp.toPx()

/** 横の目盛り線(0・1/4・2/4・3/4・最大)と、左の数字。 */
private fun DrawScope.drawGrid(
    measurer: TextMeasurer,
    style: TextStyle,
    color: Color,
    top: Int,
    left: Float,
    chartHeight: Float,
    chartWidth: Float
) {
    for (step in 0..4) {
        val value = top * step / 4
        val y = chartHeight - chartHeight * step / 4
        drawLine(color, Offset(left, y), Offset(left + chartWidth, y), strokeWidth = 1.dp.toPx())
        val layout = measurer.measure(value.toString(), style)
        drawText(layout, topLeft = Offset(left - layout.size.width - 6.dp.toPx(), y - layout.size.height / 2f))
    }
}

/** X 軸の項目名。重ならないよう、幅に応じて間引く。 */
private fun DrawScope.drawXLabels(
    measurer: TextMeasurer,
    style: TextStyle,
    labels: List<String>,
    left: Float,
    slot: Float,
    y: Float
) {
    val widest = labels.maxOf { measurer.measure(it, style).size.width } + 6.dp.toPx()
    val every = max(1, ceil(widest / slot).toInt())
    labels.forEachIndexed { i, label ->
        if (i % every != 0) return@forEachIndexed
        val layout = measurer.measure(label, style)
        drawText(layout, topLeft = Offset(left + slot * i + (slot - layout.size.width) / 2, y))
    }
}
