package com.unchunks.echomark.data.repository

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.data.local.dao.BookmarkDao
import com.unchunks.echomark.data.place.PhotoLocationReader
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.place.GeoParser
import com.unchunks.echomark.domain.place.GeoPoint
import com.unchunks.echomark.domain.repository.PlaceDetection
import com.unchunks.echomark.domain.repository.PlaceRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject

class PlaceRepositoryImpl @Inject constructor(
    private val bookmarkDao: BookmarkDao,
    private val photoLocationReader: PhotoLocationReader,
    private val dispatcherProvider: DispatcherProvider
) : PlaceRepository {

    override suspend fun setPlace(bookmarkId: Long, name: String?, point: GeoPoint?) = withContext(dispatcherProvider.io) {
        val cleanName = name?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_PLACE_NAME_LENGTH)
        bookmarkDao.updateLocation(bookmarkId, point?.latitude, point?.longitude, cleanName)
    }

    override suspend fun detectFromExisting(): PlaceDetection = withContext(dispatcherProvider.io) {
        val context = currentCoroutineContext()
        val candidates = bookmarkDao.getPlaceCandidates()
        var fromLinks = 0
        var fromPhotos = 0
        for (entity in candidates) {
            context.ensureActive()
            val point = when (entity.type) {
                BookmarkType.URL -> entity.contentUri?.let { GeoParser.fromUrl(it) }
                BookmarkType.IMAGE -> entity.filePath?.let { photoLocationReader.read(it) }
                else -> null
            } ?: continue
            // 探している間に利用者が座標を入れていたら、入れた方を残す(SQL 側で、空のときだけ書く)
            if (bookmarkDao.updateCoordinatesIfEmpty(entity.id, point.latitude, point.longitude) > 0) {
                if (entity.type == BookmarkType.URL) fromLinks++ else fromPhotos++
            }
        }
        PlaceDetection(checked = candidates.size, fromLinks = fromLinks, fromPhotos = fromPhotos)
    }

    companion object {
        /** 場所の名前の長さの上限(文字) */
        const val MAX_PLACE_NAME_LENGTH = 100
    }
}
