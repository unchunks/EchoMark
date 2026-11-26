package com.unchunks.echomark.domain.place

import com.unchunks.components.geo.AttributionPart
import com.unchunks.components.geo.GeoJson
import com.unchunks.components.geo.GeoJsonPoint
import com.unchunks.components.geo.attributionText

/**
 * 場所の画面の「地図の背景」(タイルの地図。#85)の、タイルの提供元と地図に置く点の決まり(純粋な処理)。
 * 座標の計算・GeoJSON の書き方・表示範囲は部品の geo(`MapCameraBounds`・`GeoJson`)。
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

    const val OSM_COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"

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

    /** 帰属表示を 1 行の文字にしたもの(読み上げ・テスト用) */
    fun attributionText(): String = attribution.attributionText()

    /**
     * 座標のあるまとまりを、地図に置く点の GeoJSON(FeatureCollection)の文字にする。
     * 各点の properties は、まとまりの `key`・件数 `count`・選んでいるか `selected` だけ(タイトルなどは入れない)。
     */
    fun featureCollection(groups: List<PlaceGroup>, selectedKey: String?): String = GeoJson.featureCollection(
        groups.mapNotNull { group ->
            val center = group.center ?: return@mapNotNull null
            GeoJsonPoint(
                center,
                linkedMapOf("key" to group.key, "count" to group.bookmarks.size, "selected" to (group.key == selectedKey))
            )
        }
    )
}
