package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.place.GeoPoint

/** 「場所を探す」の結果。[checked] は調べた件数、[fromLinks]・[fromPhotos] は座標を見つけた件数。 */
data class PlaceDetection(val checked: Int, val fromLinks: Int, val fromPhotos: Int) {
    val found: Int get() = fromLinks + fromPhotos
}

/** 現在地を調べた結果。 */
sealed interface CurrentLocationResult {
    data class Found(val point: GeoPoint) : CurrentLocationResult

    /** 位置情報の許可が無い */
    data object PermissionDenied : CurrentLocationResult

    /** 位置情報がオフ・時間内に分からないなど、現在地が得られなかった */
    data object Unavailable : CurrentLocationResult
}

/**
 * ブックマークの場所(#85)。座標・名前は [com.unchunks.echomark.domain.bookmark.model.Bookmark] の列に入れる。
 *
 * 座標は端末の中にすでにある情報からだけ入れる: 保存してある地図のリンク、写真の位置情報(EXIF)、利用者が入れたもの。
 * 位置情報の許可は、利用者が「現在地を入れる」を押したときだけ [CurrentLocationProvider] が求める(裏では求めない・調べない)。
 */
interface PlaceRepository {
    /** 場所を書く。[point]・[name] がどちらも null なら場所を消す。座標が無く名前だけなら、名前だけを入れる。 */
    suspend fun setPlace(bookmarkId: Long, name: String?, point: GeoPoint?)

    /**
     * 座標のまだ無いリンクと写真から、座標を探して入れる(利用者が押したときだけ呼ぶ)。
     * リンクは地図のリンクに入っている座標だけを見る(通信しない)。写真は、アプリの中に保存したファイルの位置情報だけを読む。
     * すでに座標があるものは変えない。
     */
    suspend fun detectFromExisting(): PlaceDetection
}

/** 現在地を 1 回だけ調べる(利用者が押したときだけ呼ぶ。許可は呼ぶ側が先に求める)。 */
interface CurrentLocationProvider {
    suspend fun current(): CurrentLocationResult
}
