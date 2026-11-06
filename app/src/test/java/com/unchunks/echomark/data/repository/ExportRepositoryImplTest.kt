package com.unchunks.echomark.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.entity.BookmarkNoteEntity
import com.unchunks.echomark.data.local.entity.CollectionEntity
import com.unchunks.echomark.data.local.entity.CollectionItemEntity
import com.unchunks.echomark.data.mapper.toEntity
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.export.ExportException
import com.unchunks.echomark.domain.export.ExportFormat
import com.unchunks.echomark.domain.model.TagSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** コレクション・ブックマークのファイルへの書き出し(実際の Room と、端末のファイル)。 */
@RunWith(AndroidJUnit4::class)
class ExportRepositoryImplTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: ExportRepositoryImpl
    private lateinit var out: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = ExportRepositoryImpl(
            context, db.collectionDao(), db.bookmarkDao(), db.bookmarkNoteDao(), TestDispatcherProvider(Dispatchers.Unconfined)
        )
        out = File(context.cacheDir, "export-test-${System.nanoTime()}.txt")
    }

    @After
    fun tearDown() {
        out.delete()
        db.close()
    }

    private suspend fun saveUrl(title: String, url: String): Long =
        db.bookmarkDao().insert(
            Bookmark(
                type = BookmarkType.URL, title = title, contentUri = url, summary = "要約: $title", createdAt = 1_000, lastAccessedAt = 1_000
            ).toEntity()
        )

    @Test
    fun コレクションをMarkdownで書き出すと入れた順に並び_タグとノートも入る() = runBlocking {
        val first = saveUrl("最初の記事", "https://example.com/1")
        val second = saveUrl("二つ目の記事", "https://example.com/2")
        db.tagDao().addTagsToBookmark(first, listOf("android"), TagSource.USER)
        db.bookmarkNoteDao().insert(BookmarkNoteEntity(bookmarkId = first, text = "あとで読む", quote = "引用文", createdAt = 5, updatedAt = 5))
        val collection = db.collectionDao().insert(CollectionEntity(name = "旅行の計画", description = "夏の旅行", createdAt = 1))
        db.collectionDao().insertItem(CollectionItemEntity(collection, second, addedAt = 10))
        db.collectionDao().insertItem(CollectionItemEntity(collection, first, addedAt = 20))

        val result = repository.exportCollection(collection, ExportFormat.MARKDOWN, Uri.fromFile(out).toString())

        assertEquals(2, result.exported)
        assertEquals(0, result.skipped)
        val text = out.readText()
        assertTrue(text, text.startsWith("# 旅行の計画\n\n夏の旅行\n"))
        assertTrue(text, text.indexOf("二つ目の記事") < text.indexOf("最初の記事"))
        assertTrue(text, text.contains("#android"))
        assertTrue(text, text.contains("> 引用文"))
        assertTrue(text, text.contains("あとで読む"))
    }

    @Test
    fun コレクションをブラウザのブックマークで書き出すとリンクの無いものは飛ばして数える() = runBlocking {
        val link = saveUrl("記事", "https://example.com/1")
        val memo = db.bookmarkDao().insert(
            Bookmark(type = BookmarkType.TEXT, title = "メモ", content = "本文", createdAt = 1, lastAccessedAt = 1).toEntity()
        )
        val collection = db.collectionDao().insert(CollectionEntity(name = "集め物", createdAt = 1))
        db.collectionDao().insertItem(CollectionItemEntity(collection, link, addedAt = 10))
        db.collectionDao().insertItem(CollectionItemEntity(collection, memo, addedAt = 11))

        val result = repository.exportCollection(collection, ExportFormat.BROWSER_HTML, Uri.fromFile(out).toString())

        assertEquals(1, result.exported)
        assertEquals(1, result.skipped)
        val text = out.readText()
        assertTrue(text, text.startsWith("<!DOCTYPE NETSCAPE-Bookmark-file-1>"))
        assertTrue(text, text.contains("HREF=\"https://example.com/1\""))
        assertFalse(text.contains("メモ"))
    }

    @Test
    fun ブックマーク1件を書き出すとタイトルが見出しになる() = runBlocking {
        val id = saveUrl("1件だけの記事", "https://example.com/1")

        val result = repository.exportBookmark(id, ExportFormat.MARKDOWN, Uri.fromFile(out).toString())

        assertEquals(1, result.exported)
        assertTrue(out.readText().startsWith("# 1件だけの記事\n"))
    }

    @Test
    fun 既存のファイルを選んだときは中身を置き換える() = runBlocking {
        val id = saveUrl("記事", "https://example.com/1")
        out.writeText("古い中身".repeat(500))

        repository.exportBookmark(id, ExportFormat.MARKDOWN, Uri.fromFile(out).toString())

        assertFalse(out.readText().contains("古い中身"))
    }

    @Test
    fun コレクションやブックマークが無ければ書き出さずに知らせる() = runBlocking {
        try {
            repository.exportCollection(9_999L, ExportFormat.MARKDOWN, Uri.fromFile(out).toString())
            fail("ExportException になるはず")
        } catch (e: ExportException) {
            assertTrue(e.message!!.contains("コレクション"))
        }
        try {
            repository.exportBookmark(9_999L, ExportFormat.MARKDOWN, Uri.fromFile(out).toString())
            fail("ExportException になるはず")
        } catch (e: ExportException) {
            assertTrue(e.message!!.contains("ブックマーク"))
        }
        assertFalse(out.exists())
    }

    @Test
    fun 書き込めない保存先は理由つきで失敗する() = runBlocking {
        val id = saveUrl("記事", "https://example.com/1")
        // 存在しないフォルダの下のファイルは開けない
        val bad = Uri.fromFile(File(context.cacheDir, "no-such-dir/out.md"))

        try {
            repository.exportBookmark(id, ExportFormat.MARKDOWN, bad.toString())
            fail("ExportException になるはず")
        } catch (e: ExportException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }
}
