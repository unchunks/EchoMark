package com.unchunks.echomark.domain.place

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesTest {

    private fun bookmark(id: Long, lat: Double? = null, lng: Double? = null, place: String? = null) = Bookmark(
        id = id, type = BookmarkType.URL, title = "題$id", createdAt = id, lastAccessedAt = id,
        latitude = lat, longitude = lng, placeName = place
    )

    // ---- Bookmark の場所 ----

    @Test
    fun 座標は緯度と経度の両方があるときだけ_名前だけでも場所はある() {
        assertEquals(GeoPoint(35.0, 139.0), bookmark(1, 35.0, 139.0).geoPoint)
        assertNull(bookmark(2, 35.0, null).geoPoint)
        assertNull(bookmark(3).geoPoint)
        assertTrue(bookmark(4, place = "カフェ").hasPlace)
        assertTrue(!bookmark(5, place = "  ").hasPlace)
        assertTrue(!bookmark(6).hasPlace)
    }

    // ---- まとめ方 ----

    @Test
    fun 同じ名前は_空白_全角半角_大文字小文字の違いを無視して1つにまとめる() {
        val groups = Places.group(
            listOf(
                bookmark(1, 35.0, 139.0, "Cafe ABC"),
                bookmark(2, 35.2, 139.2, " cafe abc "),
                bookmark(3, null, null, "ｃａｆｅ　ａｂｃ")
            )
        )
        assertEquals(1, groups.size)
        assertEquals("Cafe ABC", groups.single().label)
        assertEquals(3, groups.single().bookmarks.size)
        // 中心は座標のあるものの平均
        assertEquals(35.1, groups.single().center!!.latitude, 1e-9)
        assertEquals(139.1, groups.single().center!!.longitude, 1e-9)
    }

    @Test
    fun 名前だけで座標が無いまとまりは_中心が無い() {
        val group = Places.group(listOf(bookmark(1, place = "どこか"))).single()
        assertNull(group.center)
        assertEquals("どこか", group.label)
    }

    @Test
    fun 名前の無い座標は_近いものを同じ格子にまとめる() {
        val groups = Places.group(
            listOf(
                bookmark(1, 35.6801, 139.7601), bookmark(2, 35.6812, 139.7615),
                bookmark(3, 34.6937, 135.5023)
            )
        )
        assertEquals(listOf(2, 1), groups.map { it.bookmarks.size })
        assertTrue(groups.first().label.endsWith("付近"))
    }

    @Test
    fun 名前と座標の両方があるものは名前のまとまりに入る() {
        val groups = Places.group(listOf(bookmark(1, 35.0, 139.0, "A"), bookmark(2, 35.0001, 139.0001)))
        assertEquals(2, groups.size)
        assertTrue(groups.any { it.label == "A" && it.bookmarks.size == 1 })
    }

    @Test
    fun 場所の無いブックマークは含めない_多い順に並ぶ() {
        val groups = Places.group(
            listOf(
                bookmark(1), bookmark(2, place = "少"), bookmark(3, place = "多"), bookmark(4, place = "多"), bookmark(5, place = "多")
            )
        )
        assertEquals(listOf("多", "少"), groups.map { it.label })
    }

    // ---- 地図の枠 ----

    @Test
    fun 点が1つなら中央に置かれる() {
        val frame = MapFrame.fit(listOf(GeoPoint(35.0, 139.0)), 400f, 300f, 20f)
        val (x, y) = frame.toScreen(GeoPoint(35.0, 139.0))
        assertEquals(200f, x, 0.01f)
        assertEquals(150f, y, 0.01f)
    }

    @Test
    fun 全部の点が余白の内側に収まり_北が上_東が右() {
        val points = listOf(GeoPoint(35.0, 139.0), GeoPoint(36.0, 140.0), GeoPoint(35.5, 139.5))
        val frame = MapFrame.fit(points, 400f, 300f, 20f)
        val screen = points.map { frame.toScreen(it) }
        screen.forEach { (x, y) ->
            assertTrue("x=$x", x in 19.99f..380.01f)
            assertTrue("y=$y", y in 19.99f..280.01f)
        }
        // 北(緯度が大きい)ほど上(y が小さい)、東ほど右
        assertTrue(screen[1].second < screen[0].second)
        assertTrue(screen[1].first > screen[0].first)
    }

    @Test
    fun 高い緯度では経度の1度が縮む() {
        val equator = MapFrame.fit(listOf(GeoPoint(0.0, 0.0), GeoPoint(1.0, 1.0)), 400f, 400f, 0f)
        val north = MapFrame.fit(listOf(GeoPoint(60.0, 0.0), GeoPoint(61.0, 1.0)), 400f, 400f, 0f)
        fun widthOf(frame: MapFrame, lat: Double): Float =
            frame.toScreen(GeoPoint(lat, 1.0)).first - frame.toScreen(GeoPoint(lat, 0.0)).first
        fun heightOf(frame: MapFrame, lat: Double): Float =
            frame.toScreen(GeoPoint(lat, 0.0)).second - frame.toScreen(GeoPoint(lat + 1, 0.0)).second
        // 赤道付近は経度 1 度と緯度 1 度がほぼ同じ長さ。緯度 60 度では経度 1 度は半分ほど
        assertEquals(1f, widthOf(equator, 0.0) / heightOf(equator, 0.0), 0.02f)
        assertEquals(0.5f, widthOf(north, 60.0) / heightOf(north, 60.0), 0.03f)
    }

    @Test
    fun 画面の位置から緯度経度へ戻せる() {
        val frame = MapFrame.fit(listOf(GeoPoint(35.0, 139.0), GeoPoint(36.0, 140.0)), 400f, 300f, 20f)
        val point = GeoPoint(35.3, 139.6)
        val (x, y) = frame.toScreen(point)
        val back = frame.toPoint(x, y)
        assertEquals(point.latitude, back.latitude, 1e-4)
        assertEquals(point.longitude, back.longitude, 1e-4)
    }

    @Test
    fun 点が無ければ世界全体() {
        val frame = MapFrame.fit(emptyList(), 360f, 180f, 0f)
        val (x, y) = frame.toScreen(GeoPoint(0.0, 0.0))
        assertEquals(180f, x, 0.01f)
        assertEquals(90f, y, 0.01f)
    }

    // ---- 目盛り線 ----

    @Test
    fun 目盛りの間隔は線が6本以下になる大きさ() {
        assertEquals(0.005, graticuleStep(0.02), 0.0)
        assertEquals(1.0, graticuleStep(5.0), 0.0)
        assertEquals(20.0, graticuleStep(100.0), 0.0)
        assertEquals(90.0, graticuleStep(10_000.0), 0.0)
    }

    @Test
    fun 目盛り線は間隔の倍数で_端の外は含めない() {
        assertEquals(listOf(35.0, 36.0), graticuleLines(34.5, 36.5, 1.0))
        assertEquals(listOf(0.1, 0.2, 0.3), graticuleLines(0.05, 0.35, 0.1))
        assertTrue(graticuleLines(10.0, 5.0, 1.0).isEmpty())
        assertTrue(graticuleLines(0.0, 1.0, 0.0).isEmpty())
    }

    // ---- geo URI ----

    @Test
    fun geoのURIは座標をピンの名前とともに作る_かっこは符号化される() {
        val point = GeoPoint(35.6812, 139.7671)
        assertEquals("geo:35.681200,139.767100?q=35.681200,139.767100", point.toGeoUri())
        assertEquals("geo:35.681200,139.767100?q=35.681200,139.767100(%E6%9D%B1%E4%BA%AC%20%28%E9%A7%85%29)", point.toGeoUri("東京 (駅)"))
        assertEquals("35.6812, 139.7671", point.label())
    }

    @Test
    fun 範囲の外の座標は作れない() {
        assertNull(GeoPoint.of(90.1, 0.0))
        assertNull(GeoPoint.of(0.0, -180.1))
        assertNull(GeoPoint.of(Double.NaN, 0.0))
        assertEquals(GeoPoint(-90.0, 180.0), GeoPoint.of(-90.0, 180.0))
    }
}
