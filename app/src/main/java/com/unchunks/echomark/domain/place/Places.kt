package com.unchunks.echomark.domain.place

import com.unchunks.components.geo.GeoPoint
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import java.text.Normalizer
import kotlin.math.floor

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
