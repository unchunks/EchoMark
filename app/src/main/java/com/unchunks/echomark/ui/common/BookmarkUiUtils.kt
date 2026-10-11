package com.unchunks.echomark.ui.common

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.unchunks.echomark.data.attachment.attachmentFileName
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import timber.log.Timber
import java.io.File

/** URLからドメイン(www.除去)を取り出す。取れなければURLをそのまま返す */
fun extractDomain(url: String): String =
    url.toUri().host?.removePrefix("www.") ?: url

/** 既定のブラウザでURLを開く */
fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "URLを開けるアプリがありません", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 共有メニューでブックマークを送る。保存したファイルがあればファイル(+ タイトル)、
 * 無ければ URL があれば「タイトル + URL」、それも無ければ本文(メモ)を送る
 */
fun shareBookmark(context: Context, bookmark: Bookmark) {
    val fileUri = attachmentUri(context, bookmark)
    val text = bookmark.contentUri?.let { "${bookmark.title}\n$it" }
        ?: if (fileUri != null) {
            bookmark.title
        } else {
            listOfNotNull(bookmark.title, bookmark.content?.takeIf { it.isNotBlank() }).joinToString("\n\n")
        }
    val send = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_SUBJECT, bookmark.title)
        putExtra(Intent.EXTRA_TEXT, text)
        if (fileUri != null) {
            type = bookmark.mimeType ?: "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            // 受け取ったアプリが読めるよう、一時的な読み取り権限を付ける(ClipData に入れると選択画面にも引き継がれる)
            clipData = ClipData.newRawUri(bookmark.fileName, fileUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            type = "text/plain"
        }
    }
    try {
        context.startActivity(Intent.createChooser(send, "共有"))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "共有できるアプリがありません", Toast.LENGTH_SHORT).show()
    }
}

/** 保存したファイルを、ほかのアプリ(ギャラリー・PDF ビューア・プレーヤーなど)で開く */
fun openAttachment(context: Context, bookmark: Bookmark) {
    val uri = attachmentUri(context, bookmark)
    if (uri == null) {
        Toast.makeText(context, "ファイルがこの端末にありません", Toast.LENGTH_SHORT).show()
        return
    }
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, bookmark.mimeType ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(view)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "このファイルを開けるアプリがありません", Toast.LENGTH_SHORT).show()
    }
}

/** 保存したファイルを、ほかのアプリへ渡せる content:// の URI にする(ファイルが無ければ null) */
private fun attachmentUri(context: Context, bookmark: Bookmark): Uri? {
    val name = attachmentFileName(bookmark.filePath) ?: return null
    val file = File(File(context.filesDir, "attachments"), name).takeIf { it.isFile } ?: return null
    return try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (e: IllegalArgumentException) {
        Timber.w(e, "添付ファイルの URI を作れない")
        null
    }
}

/** Snackbar などに出す短いタイトル(長ければ切って「…」) */
fun Bookmark.shortTitle(maxLength: Int = 20): String =
    if (title.length <= maxLength) title else title.take(maxLength) + "…"
