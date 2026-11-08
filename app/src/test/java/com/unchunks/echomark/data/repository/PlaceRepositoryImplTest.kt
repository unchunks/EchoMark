package com.unchunks.echomark.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.dao.BookmarkDao
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.place.PhotoLocationReader
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.place.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaceRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: BookmarkDao
    private lateinit var repository: PlaceRepositoryImpl

    /** 写真のファイルの代わり: パスごとに返す座標を決める。読んだパスも覚える */
    private val photos = mutableMapOf<String, GeoPoint?>()
    private val readPaths = mutableListOf<String>()
    private val reader = object : PhotoLocationReader {
        override suspend fun read(filePath: String): GeoPoint? {
            readPaths += filePath
            return photos[filePath]
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        dao = db.bookmarkDao()
        repository = PlaceRepositoryImpl(dao, reader, TestDispatcherProvider(Dispatchers.Unconfined))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insert(
        id: Long, type: BookmarkType = BookmarkType.URL, uri: String? = null, filePath: String? = null,
        lat: Double? = null, lng: Double? = null, place: String? = null
    ) = dao.insert(
        BookmarkEntity(
            id = id, type = type, title = "題$id", contentUri = uri, filePath = filePath, createdAt = id, lastAccessedAt = id,
            latitude = lat, longitude = lng, placeName = place
        )
    )

    // ---- 場所を書く ----

    @Test
    fun 座標と名前を書き_名前は整える() = runBlocking {
        insert(1)
        repository.setPlace(1, "  東京駅  ", GeoPoint(35.68, 139.76))
        val row = dao.getById(1)!!
        assertEquals(35.68, row.latitude!!, 0.0)
        assertEquals(139.76, row.longitude!!, 0.0)
        assertEquals("東京駅", row.placeName)
    }

    @Test
    fun 名前は100文字までにする() = runBlocking {
        insert(1)
        repository.setPlace(1, "あ".repeat(150), null)
        assertEquals(100, dao.getById(1)!!.placeName!!.length)
    }

    @Test
    fun 名前だけを入れても座標は変わらず無いまま() = runBlocking {
        insert(1)
        repository.setPlace(1, "どこか", null)
        val row = dao.getById(1)!!
        assertNull(row.latitude)
        assertNull(row.longitude)
        assertEquals("どこか", row.placeName)
    }

    @Test
    fun 名前も座標も無ければ場所を消す() = runBlocking {
        insert(1, lat = 1.0, lng = 2.0, place = "x")
        repository.setPlace(1, "  ", null)
        val row = dao.getById(1)!!
        assertNull(row.latitude)
        assertNull(row.longitude)
        assertNull(row.placeName)
    }

    // ---- 場所を探す ----

    @Test
    fun 地図のリンクと写真から座標を探して入れる() = runBlocking {
        insert(1, uri = "https://www.google.com/maps/@35.68,139.76,15z")
        insert(2, BookmarkType.IMAGE, filePath = "attachments/a.jpg")
        photos["attachments/a.jpg"] = GeoPoint(34.0, 135.0)
        insert(3, uri = "https://example.com/article")
        insert(4, BookmarkType.IMAGE, filePath = "attachments/none.jpg")

        val result = repository.detectFromExisting()

        assertEquals(4, result.checked)
        assertEquals(1, result.fromLinks)
        assertEquals(1, result.fromPhotos)
        assertEquals(35.68, dao.getById(1)!!.latitude!!, 0.0)
        assertEquals(135.0, dao.getById(2)!!.longitude!!, 0.0)
        assertNull(dao.getById(3)!!.latitude)
        assertNull(dao.getById(4)!!.latitude)
    }

    @Test
    fun すでに座標があるものは調べず変えない() = runBlocking {
        insert(1, uri = "https://www.google.com/maps/@35.68,139.76,15z", lat = 10.0, lng = 20.0)
        insert(2, BookmarkType.IMAGE, filePath = "attachments/a.jpg", lat = 1.0, lng = 2.0)
        photos["attachments/a.jpg"] = GeoPoint(34.0, 135.0)

        val result = repository.detectFromExisting()

        assertEquals(0, result.checked)
        assertEquals(10.0, dao.getById(1)!!.latitude!!, 0.0)
        assertEquals(1.0, dao.getById(2)!!.latitude!!, 0.0)
        assertEquals(emptyList<String>(), readPaths)
    }

    @Test
    fun 名前だけの場所には座標を足し_名前は残す() = runBlocking {
        insert(1, uri = "https://maps.apple.com/?ll=35.68,139.76", place = "お気に入りの店")
        repository.detectFromExisting()
        val row = dao.getById(1)!!
        assertEquals(35.68, row.latitude!!, 0.0)
        assertEquals("お気に入りの店", row.placeName)
    }

    @Test
    fun 写真以外のファイルとメモは調べない() = runBlocking {
        insert(1, BookmarkType.PDF, filePath = "attachments/a.pdf")
        insert(2, BookmarkType.TEXT)
        val result = repository.detectFromExisting()
        assertEquals(0, result.checked)
        assertEquals(emptyList<String>(), readPaths)
    }

    @Test
    fun 探す結果は見つけた数の合計で数える() = runBlocking {
        insert(1, uri = "https://example.com/x")
        val result = repository.detectFromExisting()
        assertEquals(1, result.checked)
        assertEquals(0, result.found)
    }
}
