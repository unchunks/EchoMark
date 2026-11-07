package com.unchunks.echomark.domain.place

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 同じ場所にまとめたブックマーク。[center] は座標のあるものの平均(座標が無い、名前だけのまとまりは null)。
 * [key] はまとまりを見分ける文字(名前、または座標の格子)。
 */
data class PlaceGroup(val key: String, val label: String, val center: GeoPoint?, val bookmarks: List<Bookmark>)

/** 場所のあるブックマークを、地図・一覧で使うまとまりにする純粋な処理。 */
object Places {
    /** 名前の無い座標を同じ場所とみなす格子の大きさ(度)。0.05 度は約 5 km */
    const val CELL_DEGREES = 0.05

    /**
     * 同じ名前(前後の空白・全角半角・大文字小文字の違いは無視)のものを 1 つのまとまりにする。
     * 名前が無く座標だけのものは、[cellDegrees] ごとの格子でまとめる。多い順、同じなら名前順。
     */
    fun group(bookmarks: List<Bookmark>, cellDegrees: Double = CELL_DEGREES): List<PlaceGroup> {
        val named = HashMap<String, MutableList<Bookmark>>()
        val displayName = HashMap<String, String>()
        val cells = HashMap<Pair<Long, Long>, MutableList<Bookmark>>()
        for (bookmark in bookmarks) {
            val name = bookmark.placeName?.trim()?.takeIf { it.isNotEmpty() }
            val point = bookmark.geoPoint
            when {
                name != null -> {
                    val key = nameKey(name)
                    displayName.putIfAbsent(key, name)
                    named.getOrPut(key) { ArrayList() } += bookmark
                }
                point != null -> {
                    val cell = floor(point.latitude / cellDegrees).toLong() to floor(point.longitude / cellDegrees).toLong()
                    cells.getOrPut(cell) { ArrayList() } += bookmark
                }
            }
        }
        val groups = ArrayList<PlaceGroup>(named.size + cells.size)
        for ((key, members) in named) {
            groups += PlaceGroup("n:$key", displayName.getValue(key), center(members), members)
        }
        for ((cell, members) in cells) {
            val center = center(members)
            groups += PlaceGroup("c:${cell.first}:${cell.second}", center?.label()?.plus(" 付近").orEmpty(), center, members)
        }
        return groups.sortedWith(compareByDescending<PlaceGroup> { it.bookmarks.size }.thenBy { it.label })
    }

    private fun nameKey(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase()

    private fun center(members: List<Bookmark>): GeoPoint? {
        val points = members.mapNotNull { it.geoPoint }
        if (points.isEmpty()) return null
        return GeoPoint(points.map { it.latitude }.average(), points.map { it.longitude }.average())
    }
}

/**
 * 地図の絵(タイルの無い分布図)に点を置くための、緯度・経度 → 画面の位置の変換。
 * 緯度 1 度と経度 1 度の長さの違い(高い緯度ほど経度は縮む)を、中心の緯度の cos で補う。
 * 日付変更線をまたぐ範囲は考えない(両側に点があると、世界全体に広がる)。
 */
class MapFrame private constructor(
    private val centerLat: Double,
    private val centerLng: Double,
    private val pxPerDegLat: Double,
    private val pxPerDegLng: Double,
    private val width: Float,
    private val height: Float
) {
    /** 緯度・経度の位置の、画面上の x, y(左上が原点、北が上)。 */
    fun toScreen(point: GeoPoint): Pair<Float, Float> = Pair(
        (width / 2 + (point.longitude - centerLng) * pxPerDegLng).toFloat(),
        (height / 2 - (point.latitude - centerLat) * pxPerDegLat).toFloat()
    )

    /** 画面の位置(x, y)の緯度・経度(範囲の外になるときは範囲内に丸める)。 */
    fun toPoint(x: Float, y: Float): GeoPoint = GeoPoint(
        (centerLat - (y - height / 2) / pxPerDegLat).coerceIn(-GeoPoint.MAX_LATITUDE, GeoPoint.MAX_LATITUDE),
        (centerLng + (x - width / 2) / pxPerDegLng).coerceIn(-GeoPoint.MAX_LONGITUDE, GeoPoint.MAX_LONGITUDE)
    )

    /** 画面の端の緯度・経度の範囲(目盛り線を引くため)。 */
    fun visibleBounds(): Bounds {
        val topLeft = toPoint(0f, 0f)
        val bottomRight = toPoint(width, height)
        return Bounds(
            minLat = min(topLeft.latitude, bottomRight.latitude), maxLat = max(topLeft.latitude, bottomRight.latitude),
            minLng = min(topLeft.longitude, bottomRight.longitude), maxLng = max(topLeft.longitude, bottomRight.longitude)
        )
    }

    data class Bounds(val minLat: Double, val maxLat: Double, val minLng: Double, val maxLng: Double)

    companion object {
        /** 点が 1 つ(または全部同じ場所)のときに広げる、最小の範囲(度)。約 2 km */
        const val MIN_SPAN_DEGREES = 0.02

        /**
         * [points] が [width] × [height] に収まる大きさで、全体が中央に来るようにする。[padding] は端の余白。
         * 点が無ければ、世界全体を見せる。
         */
        fun fit(points: List<GeoPoint>, width: Float, height: Float, padding: Float): MapFrame {
            if (points.isEmpty()) return world(width, height)
            val minLat = points.minOf { it.latitude }
            val maxLat = points.maxOf { it.latitude }
            val minLng = points.minOf { it.longitude }
            val maxLng = points.maxOf { it.longitude }
            val centerLat = (minLat + maxLat) / 2
            val centerLng = (minLng + maxLng) / 2
            val latSpan = max(maxLat - minLat, MIN_SPAN_DEGREES)
            val lngSpan = max(maxLng - minLng, MIN_SPAN_DEGREES)
            val lngScale = max(cos(Math.toRadians(centerLat)), 0.05)
            val usableW = max(width - 2 * padding, 1f)
            val usableH = max(height - 2 * padding, 1f)
            val perDegLat = min(usableH / latSpan, usableW / (lngSpan * lngScale))
            return MapFrame(centerLat, centerLng, perDegLat, perDegLat * lngScale, width, height)
        }

        private fun world(width: Float, height: Float): MapFrame {
            val perDegLat = min(height / 180.0, width / 360.0)
            return MapFrame(0.0, 0.0, perDegLat, perDegLat, width, height)
        }
    }
}

/** 目盛り線の間隔(度)。表示している範囲の広さ [spanDegrees] に対して、線が 3〜8 本になるようにする。 */
fun graticuleStep(spanDegrees: Double): Double {
    val steps = doubleArrayOf(0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 30.0, 45.0, 90.0)
    return steps.firstOrNull { spanDegrees / it <= 6 } ?: steps.last()
}

/** [min] 以上 [max] 以下の、[step] の倍数。 */
fun graticuleLines(min: Double, max: Double, step: Double): List<Double> {
    if (step <= 0 || max < min) return emptyList()
    val first = floor(min / step).toInt() + 1
    val last = floor(max / step).toInt()
    // 浮動小数点の誤差(0.1 の倍数など)で端がずれないよう、整数の倍数から作って丸める
    return (first..last).map { (it * step * 1e6).roundToInt() / 1e6 }.filter { abs(it) <= 180.0 }
}
