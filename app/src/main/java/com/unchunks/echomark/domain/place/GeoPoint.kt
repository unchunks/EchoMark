package com.unchunks.echomark.domain.place

import java.net.URLEncoder
import java.util.Locale

/** 緯度・経度(度)。範囲の外の値は [of] では作れない。 */
data class GeoPoint(val latitude: Double, val longitude: Double) {

    /** 「35.6812, 139.7671」の形(小数点以下 4 桁 = 約 10 m)。 */
    fun label(): String = String.format(Locale.ROOT, "%.4f, %.4f", latitude, longitude)

    /**
     * 地図アプリで開く geo: URI。[title] があれば、そのピンの名前にする。
     * 座標だけを渡し、検索語としては送らない(場所の名前を地図のサービスへ渡さないため、名前はピンの表示にだけ使う)。
     */
    fun toGeoUri(title: String? = null): String {
        val coords = String.format(Locale.ROOT, "%.6f,%.6f", latitude, longitude)
        val pin = title?.trim()?.takeIf { it.isNotEmpty() } ?: return "geo:$coords?q=$coords"
        // かっこを含む名前でも壊れないよう、% エンコードする(空白は + ではなく %20)
        return "geo:$coords?q=$coords(${URLEncoder.encode(pin, "UTF-8").replace("+", "%20")})"
    }

    companion object {
        const val MAX_LATITUDE = 90.0
        const val MAX_LONGITUDE = 180.0

        /** 範囲内の有限の値なら [GeoPoint]、そうでなければ null。 */
        fun of(latitude: Double, longitude: Double): GeoPoint? {
            if (!latitude.isFinite() || !longitude.isFinite()) return null
            if (latitude !in -MAX_LATITUDE..MAX_LATITUDE || longitude !in -MAX_LONGITUDE..MAX_LONGITUDE) return null
            return GeoPoint(latitude, longitude)
        }
    }
}
