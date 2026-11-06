package com.unchunks.echomark.data.repository

import android.content.Context
import androidx.core.net.toUri
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.data.local.dao.BookmarkDao
import com.unchunks.echomark.data.local.dao.BookmarkNoteDao
import com.unchunks.echomark.data.local.dao.CollectionDao
import com.unchunks.echomark.data.mapper.toDomain
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.export.BookmarkExporter
import com.unchunks.echomark.domain.export.ExportException
import com.unchunks.echomark.domain.export.ExportFormat
import com.unchunks.echomark.domain.export.ExportItem
import com.unchunks.echomark.domain.export.ExportResult
import com.unchunks.echomark.domain.repository.ExportRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

class ExportRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val collectionDao: CollectionDao,
    private val bookmarkDao: BookmarkDao,
    private val noteDao: BookmarkNoteDao,
    private val dispatcherProvider: DispatcherProvider
) : ExportRepository {

    override suspend fun exportCollection(collectionId: Long, format: ExportFormat, uri: String): ExportResult =
        withContext(dispatcherProvider.io) {
            val collection = collectionDao.getById(collectionId) ?: throw ExportException("コレクションが見つかりません。削除された可能性があります")
            val bookmarks = collectionDao.getBookmarks(collectionId).map { it.toDomain() }
            write(uri, format, collection.name, collection.description, bookmarks)
        }

    override suspend fun exportBookmark(bookmarkId: Long, format: ExportFormat, uri: String): ExportResult =
        withContext(dispatcherProvider.io) {
            val bookmark = bookmarkDao.getByIdsWithTags(listOf(bookmarkId)).firstOrNull()?.toDomain()
                ?: throw ExportException("ブックマークが見つかりません。削除された可能性があります")
            write(uri, format, bookmark.title, null, listOf(bookmark))
        }

    private suspend fun write(
        uri: String,
        format: ExportFormat,
        title: String,
        description: String?,
        bookmarks: List<Bookmark>
    ): ExportResult {
        val items = bookmarks.map { bookmark -> ExportItem(bookmark, noteDao.getByBookmark(bookmark.id).map { it.toDomain() }) }
        val document = BookmarkExporter.render(format, title, description, items, exportedAt = System.currentTimeMillis())
        try {
            // "wt": 既存のファイルを選んだ場合も、中身を切り詰めて上書きする
            val output = context.contentResolver.openOutputStream(uri.toUri(), "wt")
                ?: throw IOException("openOutputStream returned null")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(document.text) }
        } catch (e: IOException) {
            Timber.w(e, "書き出しに失敗")
            throw ExportException("ファイルに書き込めませんでした。保存先を変えてもう一度お試しください", e)
        } catch (e: SecurityException) {
            Timber.w(e, "書き出し先にアクセスできない")
            throw ExportException("保存先にアクセスできませんでした", e)
        }
        Timber.i("書き出し: format=$format exported=${document.exported} skipped=${document.skipped}")
        return ExportResult(document.exported, document.skipped)
    }
}
