package com.unchunks.echomark.ui.share

import com.unchunks.echomark.ui.bookmark.ClipboardContent
import com.unchunks.echomark.ui.common.SelectedFile

/*
 * クイック設定のタイルから「クリップボードを保存」したときの、クリップボードの中身 → 共有シートに出す内容の対応。
 * 共有シートの保存([ShareViewModel])をそのまま使うため、共有で受け取ったときと同じ [SharedContent] にする。
 */

/** クリップボードの中身を、共有シートで保存する内容にする。保存できるものが無ければ null */
internal fun ClipboardContent.toSharedContent(): SharedContent? = when (this) {
    is ClipboardContent.Url -> SharedContent(url = url, text = url, tentativeTitle = title ?: url)
    is ClipboardContent.Text -> textSharedContent(text)
    is ClipboardContent.Image ->
        SharedContent(url = null, text = "", tentativeTitle = "", files = listOf(SelectedFile(uri, mimeType, displayName = null, sizeBytes = null)))
    ClipboardContent.Empty -> null
}

/** URL だけが並んだテキストは1件ずつのリンクに、それ以外は1件のメモにする */
private fun textSharedContent(text: String): SharedContent {
    val urls = urlOnlyLines(text)
    if (urls.size > 1) {
        val entries = urls.map { SharedEntry(url = it, text = it, title = it) }
        return SharedContent(url = null, text = urls.joinToString("\n"), tentativeTitle = "", entries = entries)
    }
    val firstLine = text.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return SharedContent(url = null, text = text, tentativeTitle = firstLine.take(TITLE_LENGTH))
}

private const val TITLE_LENGTH = 80
