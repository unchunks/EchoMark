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
 * 共有インテントから取り出した内容。[files] があればファイルとして保存する([text] は添えられた文章)。
 * ファイルが無く url が null ならテキストとして扱う
 */
data class SharedContent(
    val url: String?,
    val text: String,
    val tentativeTitle: String,
    val files: List<SelectedFile> = emptyList()
)

/** ファイルの名前・サイズ・種類を提供元に問い合わせて埋める */
internal fun SharedContent.withFileDetails(resolver: ContentResolver): SharedContent =
    if (files.isEmpty()) this else copy(files = files.map { querySelectedFile(resolver, it.uri.toUri(), it.mimeType) })

/** 共有インテント(ACTION_SEND / ACTION_SEND_MULTIPLE)から保存する内容を取り出す。保存できるものが無ければ null */
internal fun parseSharedContent(intent: Intent): SharedContent? {
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

    if (intent.action != Intent.ACTION_SEND || text.isEmpty()) return null

    val found = findUrlMatchInText(text)
    if (found != null) {
        // 「タイトル + URL」形式で共有するアプリ向けに、URL以外の部分を仮タイトルの候補にする
        val remaining = text.replace(found.matched, "").trim().lines().firstOrNull { it.isNotBlank() }?.trim()
        return SharedContent(url = found.url, text = text, tentativeTitle = subject ?: remaining ?: found.url)
    }
    val firstLine = text.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return SharedContent(url = null, text = text, tentativeTitle = subject ?: firstLine.take(80))
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
