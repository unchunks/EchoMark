package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.export.ExportException
import com.unchunks.echomark.domain.export.ExportFormat
import com.unchunks.echomark.domain.export.ExportResult

/**
 * コレクションやブックマークを、他のアプリ・サービスへ持ち出せるファイルに書き出す(#91)。
 * ファイル全体のバックアップ([DataManagementRepository])とは別で、読める形式(Markdown・ブラウザのブックマーク HTML)にする。
 */
interface ExportRepository {

    /** コレクション [collectionId] の中身を [format] で [uri] に書き出す。コレクションが無い・書き込めないときは [ExportException]。 */
    suspend fun exportCollection(collectionId: Long, format: ExportFormat, uri: String): ExportResult

    /** ブックマーク1件を [format] で [uri] に書き出す(ノートも入る)。ブックマークが無い・書き込めないときは [ExportException]。 */
    suspend fun exportBookmark(bookmarkId: Long, format: ExportFormat, uri: String): ExportResult
}
