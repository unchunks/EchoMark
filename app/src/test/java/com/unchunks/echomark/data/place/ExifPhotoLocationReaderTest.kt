package com.unchunks.echomark.data.place

import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExifPhotoLocationReaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** 1×1 ピクセルの JPEG(EXIF は無い) */
    private val tinyJpeg = Base64.decode(
        "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAAMCAgICAgMCAgIDAwMDBAYEBAQEBAgGBgUGCQgKCgkICQkKDA8MCgsOCwkJDRENDg8QEBEQCgwSExIQEw8QEBD/yQALCAABAAEBAREA/8wABgAQEAX/2gAIAQEAAD8A0s8g/9k=",
        Base64.DEFAULT
    )

    private fun jpeg(configure: (ExifInterface) -> Unit = {}): File {
        val file = folder.newFile("photo.jpg")
        file.writeBytes(tinyJpeg)
        ExifInterface(file).apply {
            configure(this)
            saveAttributes()
        }
        return file
    }

    @Test
    fun 位置情報つきの写真から緯度経度を読む() {
        val file = jpeg { it.setLatLong(35.6812, 139.7671) }
        val point = ExifPhotoLocationReader.readFrom(file)
        assertNotNull(point)
        assertEquals(35.6812, point!!.latitude, 1e-4)
        assertEquals(139.7671, point.longitude, 1e-4)
    }

    @Test
    fun 南半球と西半球も読める() {
        val file = jpeg { it.setLatLong(-33.8688, -70.1234) }
        val point = ExifPhotoLocationReader.readFrom(file)!!
        assertEquals(-33.8688, point.latitude, 1e-4)
        assertEquals(-70.1234, point.longitude, 1e-4)
    }

    @Test
    fun 位置情報の無い写真は_null() {
        assertNull(ExifPhotoLocationReader.readFrom(jpeg()))
    }

    @Test
    fun ゼロゼロは場所として扱わない() {
        val file = jpeg { it.setLatLong(0.0, 0.0) }
        assertNull(ExifPhotoLocationReader.readFrom(file))
    }

    @Test
    fun 画像でないファイルや無いファイルは_nullで落ちない() {
        val text = folder.newFile("note.txt").also { it.writeText("hello") }
        assertNull(ExifPhotoLocationReader.readFrom(text))
        assertNull(ExifPhotoLocationReader.readFrom(File(folder.root, "missing.jpg")))
    }
}
