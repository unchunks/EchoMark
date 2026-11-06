package com.unchunks.echomark.domain.place

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GeoParserTest {

    private fun assertPoint(lat: Double, lng: Double, point: GeoPoint?) {
        assertNotNull(point)
        assertEquals(lat, point!!.latitude, 1e-9)
        assertEquals(lng, point.longitude, 1e-9)
    }

    // ---- 利用者が入れた文字 ----

    @Test
    fun 緯度経度の書き方はいくつかを受け付ける() {
        assertPoint(35.6812, 139.7671, GeoParser.parseCoordinates("35.6812, 139.7671"))
        assertPoint(35.6812, 139.7671, GeoParser.parseCoordinates("35.6812,139.7671"))
        assertPoint(35.6812, 139.7671, GeoParser.parseCoordinates("  35.6812 139.7671  "))
        assertPoint(35.6812, 139.7671, GeoParser.parseCoordinates("35.6812、139.7671"))
        assertPoint(-33.8688, 151.2093, GeoParser.parseCoordinates("-33.8688, 151.2093"))
        assertPoint(35.0, 139.0, GeoParser.parseCoordinates("35, 139"))
    }

    @Test
    fun geoのURIも読める() {
        assertPoint(35.68, 139.76, GeoParser.parseCoordinates("geo:35.68,139.76"))
        assertPoint(35.68, 139.76, GeoParser.parseCoordinates("geo:35.68,139.76?z=15"))
    }

    @Test
    fun 読めない文字と範囲外は_nullになる() {
        assertNull(GeoParser.parseCoordinates(""))
        assertNull(GeoParser.parseCoordinates("東京駅"))
        assertNull(GeoParser.parseCoordinates("35.68"))
        assertNull(GeoParser.parseCoordinates("35.68, 139.76, 10"))
        assertNull(GeoParser.parseCoordinates("91.0, 10.0"))
        assertNull(GeoParser.parseCoordinates("10.0, 181.0"))
        assertNull(GeoParser.parseCoordinates("NaN, 10"))
    }

    @Test
    fun 範囲の端は受け付ける() {
        assertPoint(90.0, 180.0, GeoParser.parseCoordinates("90, 180"))
        assertPoint(-90.0, -180.0, GeoParser.parseCoordinates("-90, -180"))
    }

    // ---- 保存してあるリンク ----

    @Test
    fun Googleマップの場所のデータはピンの座標を使う() {
        val url = "https://www.google.com/maps/place/Tokyo+Station/@35.6812,139.7671,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x1!8m2!3d35.6809591!4d139.7673068"
        assertPoint(35.6809591, 139.7673068, GeoParser.fromUrl(url))
    }

    @Test
    fun Googleマップの_アットマークの位置() {
        assertPoint(35.6812, 139.7671, GeoParser.fromUrl("https://www.google.com/maps/@35.6812,139.7671,15z"))
        assertPoint(35.6812, 139.7671, GeoParser.fromUrl("https://google.co.jp/maps/@35.6812,139.7671,15z"))
        assertPoint(35.6812, 139.7671, GeoParser.fromUrl("https://maps.google.com/@35.6812,139.7671,15z"))
    }

    @Test
    fun Googleマップの_検索の座標() {
        assertPoint(35.68, 139.76, GeoParser.fromUrl("https://www.google.com/maps?q=35.68,139.76"))
        assertPoint(35.68, 139.76, GeoParser.fromUrl("https://www.google.com/maps/search/?api=1&query=35.68%2C139.76"))
    }

    @Test
    fun アップルマップとOpenStreetMap() {
        assertPoint(35.68, 139.76, GeoParser.fromUrl("https://maps.apple.com/?ll=35.68,139.76&q=Tokyo"))
        assertPoint(35.68, 139.76, GeoParser.fromUrl("https://www.openstreetmap.org/#map=15/35.68/139.76"))
        assertPoint(35.1, 139.2, GeoParser.fromUrl("https://www.openstreetmap.org/?mlat=35.1&mlon=139.2#map=15/35.68/139.76"))
    }

    @Test
    fun geoの_URIは直接の座標か_q_を使う() {
        assertPoint(35.68, 139.76, GeoParser.fromUrl("geo:35.68,139.76"))
        assertPoint(35.68, 139.76, GeoParser.fromUrl("geo:0,0?q=35.68,139.76(カフェ)"))
    }

    @Test
    fun 地図でないサイトの数字は座標にしない() {
        assertNull(GeoParser.fromUrl("https://example.com/article/@35.6812,139.7671"))
        assertNull(GeoParser.fromUrl("https://example.com/page?q=35.68,139.76"))
        assertNull(GeoParser.fromUrl("https://www.google.com/search?q=35.68,139.76"))
        assertNull(GeoParser.fromUrl("https://news.example.org/maps/@35.6812,139.7671"))
    }

    @Test
    fun 座標を含まない地図のリンクと短縮リンクは_nullになる() {
        assertNull(GeoParser.fromUrl("https://maps.app.goo.gl/abcdEFGH"))
        assertNull(GeoParser.fromUrl("https://www.google.com/maps/place/Tokyo+Station"))
        assertNull(GeoParser.fromUrl("not a url"))
        assertNull(GeoParser.fromUrl(""))
    }

    @Test
    fun 範囲外の座標のリンクは読まない() {
        assertNull(GeoParser.fromUrl("https://www.google.com/maps/@135.6812,139.7671,15z"))
        assertNull(GeoParser.fromUrl("https://www.google.com/maps/@35.6812,239.7671,15z"))
    }

    @Test
    fun ホスト名が似ているだけのサイトは地図として扱わない() {
        assertNull(GeoParser.fromUrl("https://evil-google.com/maps/@35.6812,139.7671,15z"))
        assertNull(GeoParser.fromUrl("https://www.google.com.evil.example/other/@35.6812,139.7671,15z"))
        assertNull(GeoParser.fromUrl("https://www.google.com.evil.example/maps/@35.6812,139.7671,15z"))
    }
}
