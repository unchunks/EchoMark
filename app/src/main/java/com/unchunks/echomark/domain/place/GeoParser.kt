package com.unchunks.echomark.domain.place

/**
 * 座標の読み取り(純粋な処理)。通信はしない。
 * 利用者が入れた文字・保存してある地図のリンクの中に、すでに入っている座標だけを取り出す
 * (短縮リンク `maps.app.goo.gl/...` のように、開かないと座標が分からないものは対象外)。
 */
object GeoParser {

    private const val NUMBER = """-?\d{1,3}(?:\.\d+)?"""
    private const val DECIMAL = """-?\d{1,3}\.\d+"""

    /** 「35.6812, 139.7671」「35.6812 139.7671」「35.6812、139.7671」など。 */
    private val plainPair = Regex("""^\s*($NUMBER)\s*(?:[,、，]|\s)\s*($NUMBER)\s*$""")

    private val geoScheme = Regex("""^geo:($NUMBER),($NUMBER)(?:[;?].*)?$""", RegexOption.IGNORE_CASE)
    private val geoQuery = Regex("""[?&]q=($DECIMAL),($DECIMAL)""", RegexOption.IGNORE_CASE)

    /** Google マップの場所のデータ(`!3d緯度!4d経度`)。`@` の位置は表示の中心で、ピンとずれることがあるので先に見る。 */
    private val placeData = Regex("""!3d($DECIMAL)!4d($DECIMAL)""")
    private val atPosition = Regex("""@($DECIMAL),($DECIMAL)""")
    private val queryPair = Regex(
        """[?&](?:q|ll|query|center|sll|daddr|destination)=($DECIMAL)(?:,|%2C|%2c)($DECIMAL)"""
    )
    private val osmHash = Regex("""#map=\d{1,2}/($DECIMAL)/($DECIMAL)""")
    private val osmMarkerLat = Regex("""[?&]mlat=($DECIMAL)""")
    private val osmMarkerLng = Regex("""[?&]mlon=($DECIMAL)""")

    /** 利用者が入力した「緯度, 経度」の文字。読み取れない・範囲外なら null。 */
    fun parseCoordinates(text: String): GeoPoint? {
        val trimmed = text.trim()
        geoScheme.matchEntire(trimmed)?.let { return pointOf(it) }
        val match = plainPair.matchEntire(trimmed) ?: return null
        return pointOf(match)
    }

    /**
     * 地図のリンクに入っている座標。Google マップ・Apple マップ・OpenStreetMap のリンクと geo: だけを見る
     * (ほかのサイトの URL に偶然ある数字を、座標と思わないため)。
     */
    fun fromUrl(url: String): GeoPoint? {
        val trimmed = url.trim()
        if (trimmed.startsWith("geo:", ignoreCase = true)) {
            val direct = geoScheme.matchEntire(trimmed)?.let { pointOf(it) }
            // geo:0,0?q=緯度,経度 の形は、0,0 ではなく q を使う
            val query = geoQuery.find(trimmed)?.let { pointOf(it) }
            return if (direct == null || (direct.latitude == 0.0 && direct.longitude == 0.0)) query else direct
        }
        val host = hostOf(trimmed) ?: return null
        val path = trimmed.substringAfter(host, "")
        return when {
            isGoogleMaps(host, path) ->
                placeData.find(trimmed)?.let { pointOf(it) }
                    ?: atPosition.find(trimmed)?.let { pointOf(it) }
                    ?: queryPair.find(trimmed)?.let { pointOf(it) }
            host == "maps.apple.com" -> queryPair.find(trimmed)?.let { pointOf(it) }
            host == "openstreetmap.org" || host == "www.openstreetmap.org" -> {
                val lat = osmMarkerLat.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull()
                val lng = osmMarkerLng.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull()
                if (lat != null && lng != null) GeoPoint.of(lat, lng) else osmHash.find(trimmed)?.let { pointOf(it) }
            }
            else -> null
        }
    }

    private fun pointOf(match: MatchResult): GeoPoint? {
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lng = match.groupValues[2].toDoubleOrNull() ?: return null
        return GeoPoint.of(lat, lng)
    }

    /** `https://host/...` のホスト(小文字)。http/https 以外・ホストが無ければ null。 */
    private fun hostOf(url: String): String? {
        val match = Regex("""^https?://([^/?#:@]+)""", RegexOption.IGNORE_CASE).find(url) ?: return null
        return match.groupValues[1].lowercase()
    }

    /** Google のドメイン(`google.com`・`google.co.jp`・`google.com.au` など。`www.` 付きも)。似た名前の別のサイトは含めない */
    private val googleHost = Regex("""^(?:www\.)?google\.(?:com|co\.[a-z]{2}|com\.[a-z]{2}|[a-z]{2,3})$""")
    private val mapsGoogleHost = Regex("""^maps\.google\.(?:com|co\.[a-z]{2}|com\.[a-z]{2}|[a-z]{2,3})$""")

    /** `www.google.com/maps/...`・`google.co.jp/maps/...`・`maps.google.com/...` */
    private fun isGoogleMaps(host: String, path: String): Boolean {
        if (mapsGoogleHost.matches(host)) return true
        return googleHost.matches(host) && path.startsWith("/maps")
    }
}
