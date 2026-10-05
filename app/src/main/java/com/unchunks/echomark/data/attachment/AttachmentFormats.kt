package com.unchunks.echomark.data.attachment

import com.unchunks.echomark.domain.bookmark.model.normalizeMimeType

/*
 * 添付ファイルの保存先の名前と、MIME タイプ・拡張子の対応。Android に依存しない(単体テスト・バックアップの検証でも使う)。
 */

/** 添付ファイルを置く filesDir 内のディレクトリ名 */
internal const val ATTACHMENT_DIRECTORY = "attachments"

/** 保存先のファイル名として受け付ける形(UUID + 拡張子。ディレクトリの区切りや「..」は含めない) */
private val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}(\\.[A-Za-z0-9]{1,10})?")

/**
 * [filePath](filesDir からの相対パス)が添付ファイルの置き場所を指していれば、そのファイル名を返す。
 * 別の場所を指す・おかしな名前(バックアップを書き換えたものなど)なら null。
 */
internal fun attachmentFileName(filePath: String?): String? {
    if (filePath == null || !filePath.startsWith("$ATTACHMENT_DIRECTORY/")) return null
    val name = filePath.removePrefix("$ATTACHMENT_DIRECTORY/")
    return name.takeIf { SAFE_FILE_NAME.matches(it) }
}

/** 添付ファイルの置き場所として正しい相対パスか */
internal fun isValidAttachmentPath(filePath: String?): Boolean = attachmentFileName(filePath) != null

/**
 * 拡張子 → MIME タイプ。端末の MimeTypeMap で分からないとき(古い端末・テスト環境)に使う。
 * 保存できる形式(画像・PDF・音声・動画・テキスト)のよくあるものだけ。
 */
private val MIME_TYPES_BY_EXTENSION: Map<String, String> = mapOf(
    "pdf" to "application/pdf",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "png" to "image/png",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "heic" to "image/heic",
    "heif" to "image/heif",
    "bmp" to "image/bmp",
    "mp3" to "audio/mpeg",
    "m4a" to "audio/mp4",
    "aac" to "audio/aac",
    "wav" to "audio/x-wav",
    "ogg" to "audio/ogg",
    "opus" to "audio/opus",
    "flac" to "audio/flac",
    "amr" to "audio/amr",
    "mp4" to "video/mp4",
    "webm" to "video/webm",
    "mov" to "video/quicktime",
    "3gp" to "video/3gpp",
    "mkv" to "video/x-matroska",
    "txt" to "text/plain",
    "md" to "text/markdown",
    "csv" to "text/csv",
    "html" to "text/html",
    "htm" to "text/html"
)

/** MIME タイプ → 保存するときの拡張子(上の表の逆。同じ MIME タイプが複数あるときは先のもの) */
private val EXTENSIONS_BY_MIME_TYPE: Map<String, String> =
    MIME_TYPES_BY_EXTENSION.entries.reversed().associate { (ext, mime) -> mime to ext }

/** ファイル名の拡張子(小文字)。無い・おかしな形なら null */
internal fun extensionOf(fileName: String?): String? =
    fileName?.substringAfterLast('/', fileName)
        ?.substringAfterLast('.', "")
        ?.lowercase()
        ?.takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }

/** 拡張子から MIME タイプを推定する(表にあるものだけ) */
internal fun fallbackMimeTypeOf(extension: String?): String? = extension?.let { MIME_TYPES_BY_EXTENSION[it.lowercase()] }

/** MIME タイプから拡張子を決める(表にあるものだけ) */
internal fun fallbackExtensionOf(mimeType: String?): String? = normalizeMimeType(mimeType)?.let { EXTENSIONS_BY_MIME_TYPE[it] }

/**
 * 表示用のファイル名を整える(パスの区切りを除き、長すぎれば切る)。空なら「file.拡張子」。
 */
internal fun sanitizeDisplayName(fileName: String?, extension: String?): String {
    val name = fileName
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.filterNot { it.isISOControl() }
        ?.trim()
        .orEmpty()
    if (name.isNotEmpty()) return name.take(MAX_DISPLAY_NAME_LENGTH)
    return if (extension != null) "file.$extension" else "file"
}

private const val MAX_DISPLAY_NAME_LENGTH = 200
