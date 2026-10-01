package com.unchunks.echomark.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import com.unchunks.echomark.domain.bookmark.model.Bookmark

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

/** 共有メニューでブックマークを送る。URL があれば「タイトル + URL」、無ければ本文(メモ)を送る */
fun shareBookmark(context: Context, bookmark: Bookmark) {
    val text = bookmark.contentUri?.let { "${bookmark.title}\n$it" }
        ?: listOfNotNull(bookmark.title, bookmark.content?.takeIf { it.isNotBlank() }).joinToString("\n\n")
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, bookmark.title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    try {
        context.startActivity(Intent.createChooser(send, "共有"))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "共有できるアプリがありません", Toast.LENGTH_SHORT).show()
    }
}

/** Snackbar などに出す短いタイトル(長ければ切って「…」) */
fun Bookmark.shortTitle(maxLength: Int = 20): String =
    if (title.length <= maxLength) title else title.take(maxLength) + "…"
