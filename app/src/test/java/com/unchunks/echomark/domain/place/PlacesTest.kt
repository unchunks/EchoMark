package com.unchunks.echomark.domain.place

import com.unchunks.components.geo.GeoPoint
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
}
