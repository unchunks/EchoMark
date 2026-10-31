package com.unchunks.echomark.ui.bookmark

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.view.textclassifier.TextClassifier
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.bookmarkTypeOfMimeType
import com.unchunks.echomark.domain.bookmark.model.normalizeMimeType
import com.unchunks.echomark.ui.common.findUrlInText
import com.unchunks.echomark.ui.common.normalizeUrlInput

/*
 * クリップボードから保存する(追加シートの「クリップボードから」)。
 * クリップボードの中身は、利用者が貼り付けのボタンを押したときにだけ読む。
 * 開いたときの提案には、中身ではなく種類の説明だけを使う(Android 12 以降は中身を読むとシステムが通知を出す)。
 */

/** クリップボードの中身を読まずに、説明(MIME タイプと分類結果)から分かる種類 */
enum class ClipboardHint {
    /** 保存できそうなものが無い */
    NONE,

    /** URL がありそう */
    URL,

    /** URL ではないテキスト */
    TEXT,

    /** 画像 */
    IMAGE
}

/** クリップボードから読んだ中身 */
sealed interface ClipboardContent {
    data class Url(val url: String) : ClipboardContent

    data class Text(val text: String) : ClipboardContent

    /** 画像(content:// の URI。読み取り権限は貼り付けを押した間だけ使える) */
    data class Image(val uri: String, val mimeType: String) : ClipboardContent

    /** 保存できるものが無い */
    data object Empty : ClipboardContent
}

/** URL の分類結果がこれ以上なら URL がありそうとみなす */
private const val URL_CONFIDENCE = 0.5f

/**
 * 説明から分かる種類を決める。[mimeTypes] はクリップボードの MIME タイプ、[urlConfidence] は URL の分類結果の自信
 * (分類がまだ・使えないときは null)。
 */
internal fun clipboardHintOf(mimeTypes: List<String>, urlConfidence: Float?): ClipboardHint {
    val types = mimeTypes.mapNotNull { normalizeMimeType(it) }
    if (types.any { bookmarkTypeOfMimeType(it) == BookmarkType.IMAGE }) return ClipboardHint.IMAGE
    val isText = types.any { it == "text/plain" || it == "text/uri-list" || it == "text/html" }
    if (!isText) return ClipboardHint.NONE
    return if (urlConfidence != null && urlConfidence >= URL_CONFIDENCE) ClipboardHint.URL else ClipboardHint.TEXT
}

/** クリップボードの文字列を、URL(文章の中の URL も拾う)・テキスト・空に分ける */
internal fun classifyClipboardText(text: String): ClipboardContent {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ClipboardContent.Empty
    val url = findUrlInText(trimmed) ?: normalizeUrlInput(trimmed)
    // 文章の中に URL があるだけの長い文章は、URL ではなくテキストとして保存する(URL だけ、または短い「題名 + URL」のとき URL にする)
    return if (url != null && isMostlyUrl(trimmed, url)) ClipboardContent.Url(url) else ClipboardContent.Text(trimmed)
}

/** [text] の大半が URL か(URL 以外の部分が [MAX_EXTRA_CHARS_AROUND_URL] 文字以下) */
private fun isMostlyUrl(text: String, url: String): Boolean = text.length - url.length <= MAX_EXTRA_CHARS_AROUND_URL

private const val MAX_EXTRA_CHARS_AROUND_URL = 80

/** 開いたときの提案用。クリップボードの中身は読まず、説明だけで種類を決める。 */
internal fun clipboardHint(context: Context): ClipboardHint {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return ClipboardHint.NONE
    val description = clipboard.primaryClipDescription ?: return ClipboardHint.NONE
    val mimeTypes = (0 until description.mimeTypeCount).map { description.getMimeType(it) }
    val confidence = if (description.classificationStatus == ClipDescription.CLASSIFICATION_COMPLETE) {
        description.getConfidenceScore(TextClassifier.TYPE_URL)
    } else {
        null
    }
    return clipboardHintOf(mimeTypes, confidence)
}

/** クリップボードの中身を読む(利用者が貼り付けを押したときだけ呼ぶ)。 */
internal fun readClipboard(context: Context): ClipboardContent {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return ClipboardContent.Empty
    val clip = clipboard.primaryClip ?: return ClipboardContent.Empty
    if (clip.itemCount == 0) return ClipboardContent.Empty
    val item = clip.getItemAt(0)

    val uri = item.uri
    if (uri != null && uri.scheme == ContentResolver.SCHEME_CONTENT) {
        val mimeType = (0 until clip.description.mimeTypeCount).map { clip.description.getMimeType(it) }
            .firstOrNull { bookmarkTypeOfMimeType(it) == BookmarkType.IMAGE }
            ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (bookmarkTypeOfMimeType(mimeType) == BookmarkType.IMAGE) {
            return ClipboardContent.Image(uri.toString(), normalizeMimeType(mimeType)!!)
        }
    }
    return classifyClipboardText(item.coerceToText(context)?.toString().orEmpty())
}
