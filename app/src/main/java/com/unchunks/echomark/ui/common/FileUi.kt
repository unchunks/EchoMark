package com.unchunks.echomark.ui.common

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.graphics.vector.ImageVector
import com.unchunks.echomark.data.attachment.extensionOf
import com.unchunks.echomark.data.attachment.fallbackMimeTypeOf
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.MAX_ATTACHMENT_BYTES
import com.unchunks.echomark.domain.bookmark.model.bookmarkTypeOfMimeType
import com.unchunks.echomark.domain.bookmark.model.normalizeMimeType
import timber.log.Timber
import java.util.Locale

/** 種類の表示名(一覧の出どころ・ファイルの説明に使う) */
fun BookmarkType.displayName(): String = when (this) {
    BookmarkType.URL -> "リンク"
    BookmarkType.TEXT -> "メモ"
    BookmarkType.IMAGE -> "画像"
    BookmarkType.PDF -> "PDF"
    BookmarkType.AUDIO -> "音声"
    BookmarkType.VIDEO -> "動画"
}

/** 種類のアイコン */
fun BookmarkType.icon(): ImageVector = when (this) {
    BookmarkType.URL -> Icons.Outlined.Link
    BookmarkType.TEXT -> Icons.AutoMirrored.Outlined.Notes
    BookmarkType.IMAGE -> Icons.Outlined.Image
    BookmarkType.PDF -> Icons.Outlined.PictureAsPdf
    BookmarkType.AUDIO -> Icons.Outlined.Audiotrack
    BookmarkType.VIDEO -> Icons.Outlined.Movie
}

/** ファイルの種類の表示名(テキストファイルは「メモ」ではなく「テキスト」) */
fun BookmarkType.fileKindName(): String = if (this == BookmarkType.TEXT) "テキスト" else displayName()

/** 「12 KB」「3.4 MB」「1.2 GB」のような表示。小さいサイズも 0 にならないようにする */
fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        bytes <= 0 -> "0 KB"
        gb >= 1 -> String.format(Locale.ROOT, "%.1f GB", gb)
        mb >= 10 -> String.format(Locale.ROOT, "%.0f MB", mb)
        mb >= 1 -> String.format(Locale.ROOT, "%.1f MB", mb)
        else -> String.format(Locale.ROOT, "%.0f KB", kb.coerceAtLeast(1.0))
    }
}

/** 再生時間の「1:05」「1:02:03」表示 */
fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

/**
 * 保存しようとしているファイル(共有された・選んだもの。まだアプリ内にコピーしていない)。
 * @property uri content:// の URI 文字列
 */
data class SelectedFile(
    val uri: String,
    val mimeType: String?,
    val displayName: String?,
    val sizeBytes: Long?
) {
    /** 保存したときの種類。保存できない形式なら null */
    val type: BookmarkType? get() = bookmarkTypeOfMimeType(mimeType)

    /** 大きすぎて保存できない */
    val isTooLarge: Boolean get() = (sizeBytes ?: 0L) > MAX_ATTACHMENT_BYTES

    /** 保存できそうか(形式と大きさ) */
    val canSave: Boolean get() = type != null && !isTooLarge

    /** 「PDF · 2.4 MB」のような説明 */
    val description: String
        get() = listOfNotNull(type?.fileKindName() ?: "未対応の形式", sizeBytes?.takeIf { it >= 0 }?.let(::formatFileSize))
            .joinToString(" · ")

    companion object {
        /** rememberSaveable 用(画面の回転などで選んだファイルを失わない) */
        val ListSaver: Saver<List<SelectedFile>, ArrayList<String>> = Saver(
            save = { list ->
                ArrayList(list.flatMap { listOf(it.uri, it.mimeType.orEmpty(), it.displayName.orEmpty(), (it.sizeBytes ?: -1L).toString()) })
            },
            restore = { flat ->
                flat.chunked(4).map { (uri, mime, name, size) ->
                    SelectedFile(uri, mime.ifEmpty { null }, name.ifEmpty { null }, size.toLong().takeIf { it >= 0 })
                }
            }
        )
    }
}

/**
 * [uri] のファイル名・サイズ・種類を提供元に問い合わせる。種類が分からなければ [fallbackMimeType]
 * (共有の intent の種類など)、それも無ければファイル名の拡張子から推定する。
 */
fun querySelectedFile(resolver: ContentResolver, uri: Uri, fallbackMimeType: String? = null): SelectedFile {
    var name: String? = null
    var size: Long? = null
    try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
    } catch (e: Exception) {
        // 権限の失効・提供元の不具合。名前とサイズは分からないまま続ける(保存時に改めて確かめる)
        Timber.w(e, "ファイルの情報を取得できない")
    }
    val displayName = name ?: uri.lastPathSegment
    val reported = runCatching { normalizeMimeType(resolver.getType(uri)) }.getOrNull()
        ?.takeUnless { it == "application/octet-stream" }
    val fallback = normalizeMimeType(fallbackMimeType)?.takeUnless { it.endsWith("/*") || it == "application/octet-stream" }
    return SelectedFile(
        uri = uri.toString(),
        mimeType = reported ?: fallback ?: fallbackMimeTypeOf(extensionOf(displayName)),
        displayName = displayName,
        sizeBytes = size
    )
}
