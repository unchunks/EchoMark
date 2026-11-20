package com.unchunks.echomark.domain.place

import kotlin.math.max
import kotlin.math.min

/** 帰属表示の 1 片(文字と、あればリンク先)。 */
data class AttributionPart(val text: String, val url: String? = null)

/** 地図の表示範囲(南西〜北東の緯度・経度)。点が 1 つ(または全部同じ場所)のときは [isSinglePoint]。 */
data class MapCameraBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    val isSinglePoint: Boolean
) {
    val centerLatitude: Double get() = (south + north) / 2
    val centerLongitude: Double get() = (west + east) / 2
}

/**
 * 場所の画面の「地図の背景」(タイルの地図。#85)の、地図のライブラリに依らない部分(純粋な処理)。
 *
 * タイルのサーバーに送るのは、表示する範囲のタイルの番号(z/x/y)・IP アドレス・アプリ名の User-Agent だけ。
 * ブックマークのタイトル・URL・メモ・場所の名前は、地図の中(端末の中)の GeoJSON にだけ入れ、どこにも送らない。
 */
object PlacesTileMap {
    /**
     * タイルのスタイル(OpenFreeMap の Liberty)の URL。**タイルの提供元を替える・自前で持つときは、ここだけを直す**
     * (帰属表示は [attribution] も合わせて直す)。
     */
    const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

    /** スタイルが読めるまで待つ秒数。これを過ぎたら読めなかったものとして、分布図に戻す */
    const val STYLE_TIMEOUT_SECONDS = 15L

    /** 点が 1 つのときに合わせる拡大(街区が見えるくらい) */
    const val SINGLE_POINT_ZOOM = 12.0

    /** 点を置く GeoJSON のソースと、そのレイヤーの ID */
    const val SOURCE_ID = "echomark-places"
    const val LAYER_ID = "echomark-places-circles"

    /**
     * 地図に必ず出す帰属表示。OpenFreeMap の指定(<https://openfreemap.org/> の「Attribution」)は
     * 「OpenFreeMap © OpenMapTiles Data from OpenStreetMap」(それぞれ openfreemap.org・openmaptiles.org・OSM の著作権のページへのリンク)で、
     * OpenStreetMap には「© OpenStreetMap contributors」と書いて、OSM の著作権のページへリンクする。
     */
    val attribution: List<AttributionPart> = listOf(
        AttributionPart("OpenFreeMap", "https://openfreemap.org"),
        AttributionPart(" "),
        AttributionPart("© OpenMapTiles", "https://www.openmaptiles.org/"),
        AttributionPart(" Data from "),
        AttributionPart("© OpenStreetMap contributors", OSM_COPYRIGHT_URL)
    )

    const val OSM_COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"

    /** 帰属表示を 1 行の文字にしたもの(読み上げ・テスト用) */
    fun attributionText(): String = attribution.joinToString("") { it.text }

    /**
     * 座標のあるまとまりを、地図に置く点の GeoJSON(FeatureCollection)の文字にする。
     * 各点の properties は、まとまりの `key`・件数 `count`・選んでいるか `selected` だけ(タイトルなどは入れない)。
     */
    fun featureCollection(groups: List<PlaceGroup>, selectedKey: String?): String = buildString {
        append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        for (group in groups) {
            val center = group.center ?: continue
            if (!first) append(',')
            first = false
            append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
            append(center.longitude).append(',').append(center.latitude)
            append("]},\"properties\":{\"key\":").append(jsonString(group.key))
            append(",\"count\":").append(group.bookmarks.size)
            append(",\"selected\":").append(group.key == selectedKey).append("}}")
        }
        append("]}")
    }

    /** 点がすべて入る範囲。点が無ければ null。広がりが小さいときは [MapFrame.MIN_SPAN_DEGREES] まで広げる。 */
    fun cameraBounds(points: List<GeoPoint>): MapCameraBounds? {
        if (points.isEmpty()) return null
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLng = points.minOf { it.longitude }
        val maxLng = points.maxOf { it.longitude }
        val single = maxLat - minLat < MapFrame.MIN_SPAN_DEGREES && maxLng - minLng < MapFrame.MIN_SPAN_DEGREES
        val halfLat = max(maxLat - minLat, MapFrame.MIN_SPAN_DEGREES) / 2
        val halfLng = max(maxLng - minLng, MapFrame.MIN_SPAN_DEGREES) / 2
        val centerLat = (minLat + maxLat) / 2
        val centerLng = (minLng + maxLng) / 2
        return MapCameraBounds(
            south = max(centerLat - halfLat, -GeoPoint.MAX_LATITUDE),
            west = max(centerLng - halfLng, -GeoPoint.MAX_LONGITUDE),
            north = min(centerLat + halfLat, GeoPoint.MAX_LATITUDE),
            east = min(centerLng + halfLng, GeoPoint.MAX_LONGITUDE),
            isSinglePoint = single
        )
    }

    /** JSON の文字列(引用符つき)。制御文字・引用符・バックスラッシュを避ける。 */
    internal fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c.code < 0x20 -> append(String.format("\\u%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
