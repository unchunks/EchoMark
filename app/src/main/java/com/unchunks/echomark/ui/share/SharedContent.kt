package com.unchunks.echomark.ui.share

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import com.unchunks.echomark.ui.common.SelectedFile
import com.unchunks.echomark.ui.common.findUrlMatchInText
import com.unchunks.echomark.ui.common.querySelectedFile

/**
 * まとめて共有された中の1件(URL またはテキスト)。
 * @property url URL なら、その URL(リンクとして保存する)
 * @property text 共有された文章そのまま(URL でなければ、メモの本文にする)
 * @property title タイトルの候補
 */
data class SharedEntry(val url: String?, val text: String, val title: String)

/**
 * 共有インテントから取り出した内容。[files] があればファイルとして保存する([text] は添えられた文章)。
 * [entries] が2件以上なら、1件ずつ別のブックマークとして保存する(URL はリンク、それ以外はメモ)。
 * ファイルも [entries] も無く url が null ならテキストとして扱う。[isQuote] なら、選んだ文章の引用
 */
data class SharedContent(
    val url: String?,
    val text: String,
    val tentativeTitle: String,
    val files: List<SelectedFile> = emptyList(),
    val entries: List<SharedEntry> = emptyList(),
    /** 選んだ文章を引用として保存する(本文は各行の頭に「> 」を付けた形で、「引用」のタグを添える) */
    val isQuote: Boolean = false
) {
    /** 2件以上のテキスト・URL をまとめて受け取った */
    val hasMultipleEntries: Boolean get() = files.isEmpty() && entries.size > 1
}

/** ファイルの名前・サイズ・種類を提供元に問い合わせて埋める */
internal fun SharedContent.withFileDetails(resolver: ContentResolver): SharedContent =
    if (files.isEmpty()) this else copy(files = files.map { querySelectedFile(resolver, it.uri.toUri(), it.mimeType) })

/**
 * 共有インテント(ACTION_SEND / ACTION_SEND_MULTIPLE)、または文字を選んだメニュー(ACTION_PROCESS_TEXT)から、
 * 保存する内容を取り出す。保存できるものが無ければ null
 */
internal fun parseSharedContent(intent: Intent): SharedContent? {
    if (intent.action == Intent.ACTION_PROCESS_TEXT) return quoteContentOf(intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty())
    if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return null
    val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
    val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf { it.isNotEmpty() }

    // テキストの共有(text/plain で本文がある)以外で、ファイルが付いていればファイルとして扱う
    val isPlainTextShare = intent.type?.startsWith("text/plain") == true && text.isNotEmpty()
    val streams = if (isPlainTextShare) emptyList() else sharedStreams(intent)
    if (streams.isNotEmpty()) {
        val files = streams.map { SelectedFile(uri = it.toString(), mimeType = intent.type, displayName = null, sizeBytes = null) }
        return SharedContent(url = null, text = text, tentativeTitle = subject.orEmpty(), files = files)
    }

    // 複数のテキスト・URL(ACTION_SEND_MULTIPLE の EXTRA_TEXT)、または URL だけが並んだテキスト
    val texts = if (intent.action == Intent.ACTION_SEND_MULTIPLE) sharedTexts(intent) else urlOnlyLines(text)
    if (texts.size > 1) {
        val entries = texts.map(::entryOf)
        return SharedContent(url = null, text = texts.joinToString("\n"), tentativeTitle = subject.orEmpty(), entries = entries)
    }

    // 1件だけ入った複数共有は、ふつうのテキストの共有と同じに扱う
    val single = if (intent.action == Intent.ACTION_SEND_MULTIPLE) texts.singleOrNull().orEmpty() else text
    if (single.isEmpty()) return null
    val entry = entryOf(single)
    return SharedContent(url = entry.url, text = single, tentativeTitle = subject ?: entry.title)
}

/** 文章1件を URL またはメモの候補にする。「タイトル + URL」形式のときは URL 以外の部分を仮タイトルの候補にする */
private fun entryOf(text: String): SharedEntry {
    val found = findUrlMatchInText(text)
    if (found != null) {
        val remaining = text.replace(found.matched, "").trim().lines().firstOrNull { it.isNotBlank() }?.trim()
        return SharedEntry(url = found.url, text = text, title = remaining ?: found.url)
    }
    val firstLine = text.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return SharedEntry(url = null, text = text, title = firstLine.take(MAX_TENTATIVE_TITLE))
}

/**
 * ACTION_SEND_MULTIPLE で共有されたテキスト(EXTRA_TEXT のリスト。無ければ ClipData のテキスト)。
 * 空は除き、重複も除く。多すぎるときは先頭から [MAX_SHARED_ENTRIES] 件まで。
 */
private fun sharedTexts(intent: Intent): List<String> {
    val fromExtra = intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT).orEmpty().map { it.toString() }
    val fromClip = intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).text?.toString() } }.orEmpty()
    return fromExtra.ifEmpty { fromClip }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_SHARED_ENTRIES)
}

/**
 * [text] の空でない行がすべて URL だけのとき、その URL の一覧(2件以上のときだけ意味がある)。
 * 記事の本文のように URL 以外の文章が混ざっているときは、1件のテキストとして扱うので空のリストを返す。
 */
internal fun urlOnlyLines(text: String): List<String> {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.size < 2) return emptyList()
    val urls = lines.map { line -> findUrlMatchInText(line)?.takeIf { it.matched == line }?.url ?: return emptyList() }
    return urls.distinct().take(MAX_SHARED_ENTRIES)
}

/**
 * 共有されたファイルの URI。content:// だけを受け付ける(file:// を受け付けると、他のアプリに
 * このアプリの内部ファイルを読み込ませられるため)。重複は除き、多すぎるときは先頭から [MAX_SHARED_FILES] 件まで。
 */
private fun sharedStreams(intent: Intent): List<Uri> {
    val fromExtra = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    } else {
        listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
    }
    // EXTRA_STREAM を付けず ClipData だけで渡すアプリもある
    val fromClip = intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
    return (fromExtra.ifEmpty { fromClip })
        .filter { it.scheme == ContentResolver.SCHEME_CONTENT }
        .distinct()
        .take(MAX_SHARED_FILES)
}

/** 1回の共有で保存するファイルの最大数 */
private const val MAX_SHARED_FILES = 50

/** 1回の共有で保存するテキスト・URL の最大数 */
private const val MAX_SHARED_ENTRIES = 50

/** 仮タイトルにするテキストの最大の長さ */
private const val MAX_TENTATIVE_TITLE = 80

/** 選んだ文章を引用として保存する内容にする。空なら null。タイトルの候補は引用の最初の行 */
internal fun quoteContentOf(selected: String): SharedContent? {
    val text = selected.trim()
    if (text.isEmpty()) return null
    val firstLine = text.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return SharedContent(url = null, text = asQuote(text), tentativeTitle = firstLine.take(MAX_TENTATIVE_TITLE), isQuote = true)
}

/** 各行の頭に「> 」を付ける(空行は「>」だけ)。Markdown の引用の書き方 */
internal fun asQuote(text: String): String =
    text.lines().joinToString("\n") { line -> if (line.isBlank()) ">" else "> ${line.trimEnd()}" }

/** 引用を付けて保存するときに、入力欄に最初から入れておくタグ */
internal const val QUOTE_TAG = "引用"
