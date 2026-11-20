package com.unchunks.echomark.data.place

import androidx.exifinterface.media.ExifInterface
import com.unchunks.echomark.data.attachment.AttachmentStore
import com.unchunks.echomark.domain.place.GeoPoint
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/** アプリの中に保存した写真から、撮影場所(EXIF の GPS)を読む。 */
interface PhotoLocationReader {
    /** [filePath](filesDir からの相対パス)の写真の位置情報。無い・読めない・(0, 0) なら null。 */
    suspend fun read(filePath: String): GeoPoint?
}

class ExifPhotoLocationReader @Inject constructor(
    private val attachmentStore: AttachmentStore
) : PhotoLocationReader {

    override suspend fun read(filePath: String): GeoPoint? {
        val file = attachmentStore.existingFile(filePath) ?: return null
        return readFrom(file)
    }

    internal companion object {
        /**
         * [file] の位置情報。位置情報を持たない形式(GIF など)・壊れたファイルは null。
         * (0, 0) は、位置情報が取れなかったカメラが入れる値なので、場所とは扱わない。
         */
        fun readFrom(file: File): GeoPoint? = try {
            ExifInterface(file).latLong
                ?.let { GeoPoint.of(it[0], it[1]) }
                ?.takeUnless { it.latitude == 0.0 && it.longitude == 0.0 }
        } catch (e: Exception) {
            Timber.d(e, "写真の位置情報を読めなかった")
            null
        }
    }
}
