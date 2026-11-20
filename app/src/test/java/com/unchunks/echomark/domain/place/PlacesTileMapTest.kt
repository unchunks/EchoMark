package com.unchunks.echomark.domain.place

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesTileMapTest {

    private fun group(key: String, label: String, lat: Double?, lng: Double?, count: Int = 1) = PlaceGroup(
        key = key, label = label, center = if (lat != null && lng != null) GeoPoint(lat, lng) else null,
        bookmarks = (1..count).map {
            Bookmark(id = it.toLong(), type = BookmarkType.URL, title = "秘密のタイトル$it", createdAt = 0, lastAccessedAt = 0, placeName = label)
        }
    )

    // ---- 帰属表示 ----

    @Test
    fun 帰属表示はOpenFreeMapの指定の文言を全部含み_リンク先は公式のページ() {
        assertEquals("OpenFreeMap © OpenMapTiles Data from © OpenStreetMap contributors", PlacesTileMap.attributionText())
        val links = PlacesTileMap.attribution.filter { it.url != null }.associate { it.text to it.url }
        assertEquals(
            mapOf(
                "OpenFreeMap" to "https://openfreemap.org",
                "© OpenMapTiles" to "https://www.openmaptiles.org/",
                "© OpenStreetMap contributors" to "https://www.openstreetmap.org/copyright"
            ),
            links
        )
        assertEquals(PlacesTileMap.OSM_COPYRIGHT_URL, links["© OpenStreetMap contributors"])
    }

    @Test
    fun 帰属表示のリンクはすべてhttps() {
        assertTrue(PlacesTileMap.attribution.mapNotNull { it.url }.all { it.startsWith("https://") })
    }

    @Test
    fun タイルのスタイルはOpenFreeMapのhttps() {
        // 提供元を替えるときはこの定数だけを直す(帰属表示も合わせる)
        assertTrue(PlacesTileMap.STYLE_URL.startsWith("https://tiles.openfreemap.org/"))
    }

    // ---- GeoJSON ----

    @Test
    fun 座標のあるまとまりだけを点にして_件数と選択を持つ() {
        val json = PlacesTileMap.featureCollection(
            listOf(group("n:a", "A", 35.0, 139.0, count = 3), group("n:b", "座標なし", null, null), group("c:1:2", "B", 34.5, 135.5)),
            selectedKey = "c:1:2"
        )
        assertEquals(
            "{\"type\":\"FeatureCollection\",\"features\":[" +
                "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[139.0,35.0]},\"properties\":{\"key\":\"n:a\",\"count\":3,\"selected\":false}}," +
                "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[135.5,34.5]},\"properties\":{\"key\":\"c:1:2\",\"count\":1,\"selected\":true}}" +
                "]}",
            json
        )
    }

    @Test
    fun 点にブックマークのタイトルや場所の名前は入れない() {
        val json = PlacesTileMap.featureCollection(listOf(group("n:key", "自宅の近くのカフェ", 35.0, 139.0, count = 2)), null)
        assertFalse(json.contains("秘密のタイトル"))
        assertFalse(json.contains("自宅の近くのカフェ"))
        // 入っているのは、まとまりのキー・件数・選んでいるか・座標だけ
        assertTrue(json.contains("\"key\":\"n:key\""))
    }

    @Test
    fun まとまりのキーに引用符や改行があっても_JSONとして壊れない() {
        val key = "n:引用\"符\\と\n改行\t"
        val json = PlacesTileMap.featureCollection(listOf(group(key, "x", 1.0, 2.0)), null)
        assertTrue(json.contains("\"key\":\"n:引用\\\"符\\\\と\\n改行\\t\""))
    }

    @Test
    fun 点が無ければ空のFeatureCollection() {
        assertEquals("{\"type\":\"FeatureCollection\",\"features\":[]}", PlacesTileMap.featureCollection(emptyList(), null))
    }

    @Test
    fun 制御文字はユニコードの形に直す() {
        assertEquals("\"a\\u0001b\"", PlacesTileMap.jsonString("a\u0001b"))
    }

    // ---- カメラ ----

    @Test
    fun 点が無ければカメラの範囲も無い() {
        assertNull(PlacesTileMap.cameraBounds(emptyList()))
    }

    @Test
    fun 点が1つなら小さい範囲に広げ_単一の点として扱う() {
        val bounds = PlacesTileMap.cameraBounds(listOf(GeoPoint(35.0, 139.0)))!!
        assertTrue(bounds.isSinglePoint)
        assertEquals(35.0, bounds.centerLatitude, 1e-9)
        assertEquals(139.0, bounds.centerLongitude, 1e-9)
        assertEquals(MapFrame.MIN_SPAN_DEGREES, bounds.north - bounds.south, 1e-9)
    }

    @Test
    fun 複数の点はすべて入る範囲() {
        val bounds = PlacesTileMap.cameraBounds(listOf(GeoPoint(35.0, 139.0), GeoPoint(34.0, 135.0), GeoPoint(43.0, 141.0)))!!
        assertFalse(bounds.isSinglePoint)
        assertEquals(34.0, bounds.south, 1e-9)
        assertEquals(43.0, bounds.north, 1e-9)
        assertEquals(135.0, bounds.west, 1e-9)
        assertEquals(141.0, bounds.east, 1e-9)
    }

    @Test
    fun 極に近い点でも範囲は緯度経度の範囲に収まる() {
        val bounds = PlacesTileMap.cameraBounds(listOf(GeoPoint(90.0, 180.0)))!!
        assertTrue(bounds.north <= 90.0 && bounds.east <= 180.0)
    }
}
