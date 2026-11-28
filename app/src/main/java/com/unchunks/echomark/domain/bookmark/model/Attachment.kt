package com.unchunks.echomark.domain.bookmark.model

/**
 * アプリ内に保存したファイル(画像・PDF・音声・動画・テキスト)。
 *
 * @property filePath filesDir からの相対パス(`attachments/<UUID>.<拡張子>`)。絶対パスは `File(context.filesDir, filePath)`
 * @property mimeType 中身の MIME タイプ(パラメータなし・小文字)
 * @property fileName 元のファイル名(表示用)
 * @property fileSize サイズ(バイト)
 */
data class StoredAttachment(
    val filePath: String,
    val mimeType: String,
    val fileName: String,
    val fileSize: Long
)

/** 保存できるファイル1件の大きさの上限(端末から選んだ・共有されたファイル) */
const val MAX_ATTACHMENT_BYTES: Long = 200L * 1024 * 1024

/** リンク先からダウンロードして保存するファイルの大きさの上限(裏で通信するため、端末のファイルより小さくする) */
const val MAX_DOWNLOAD_BYTES: Long = 100L * 1024 * 1024

/** ファイルを選ぶ・共有を受けるときに受け付ける MIME タイプ */
val SUPPORTED_ATTACHMENT_MIME_TYPES: List<String> =
    listOf("image/*", "application/pdf", "audio/*", "video/*", "text/*")

/** MIME タイプ(パラメータを除いた小文字)。空なら null */
fun normalizeMimeType(mimeType: String?): String? =
    mimeType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it.contains('/') }

/** ファイルの MIME タイプから保存形式を決める。保存できない形式なら null。 */
fun bookmarkTypeOfMimeType(mimeType: String?): BookmarkType? {
    val type = normalizeMimeType(mimeType) ?: return null
    return when {
        type == "application/pdf" -> BookmarkType.PDF
        type.startsWith("image/") -> BookmarkType.IMAGE
        type.startsWith("audio/") -> BookmarkType.AUDIO
        type.startsWith("video/") -> BookmarkType.VIDEO
        type.startsWith("text/") -> BookmarkType.TEXT
        else -> null
    }
}

/** リンク先が HTML 以外のとき、ダウンロードして保存する形式か(PDF・画像・音声・動画) */
fun isDownloadableMimeType(mimeType: String?): Boolean =
    when (bookmarkTypeOfMimeType(mimeType)) {
        BookmarkType.PDF, BookmarkType.IMAGE, BookmarkType.AUDIO, BookmarkType.VIDEO -> true
        else -> false
    }

/** ファイル名から拡張子を除いたもの(タイトルの既定値)。空になるならファイル名のまま */
fun titleFromFileName(fileName: String): String {
    val trimmed = fileName.trim()
    val base = trimmed.substringBeforeLast('.', trimmed).trim()
    return base.ifEmpty { trimmed }
}

/** ファイルを保存できなかった理由。[userMessage] はそのまま画面に出せる文言 */
sealed class AttachmentError(val userMessage: String) {
    data class TooLarge(val limitBytes: Long) :
        AttachmentError("ファイルが大きすぎます(1件 ${limitBytes / (1024 * 1024)}MB まで)")

    data object InsufficientStorage : AttachmentError("端末の空き容量が足りません。空きを増やしてからお試しください")

    data class Unsupported(val mimeType: String?) :
        AttachmentError("この形式のファイルは保存できません(画像・PDF・音声・動画・テキストに対応)")

    data object Empty : AttachmentError("ファイルが空です")

    data object ReadFailed : AttachmentError("ファイルを読み込めませんでした")
}

/** ファイルを保存できなかった。[error] に理由と画面に出す文言を持つ */
class AttachmentException(val error: AttachmentError, cause: Throwable? = null) :
    Exception(error.userMessage, cause)
