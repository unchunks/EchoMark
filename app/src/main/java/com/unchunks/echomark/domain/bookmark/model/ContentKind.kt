package com.unchunks.echomark.domain.bookmark.model

import java.net.URI

/**
 * 要約の仕方を決める「中身の種類」。保存形式([BookmarkType])・リンク先のホスト・MIME タイプから決める。
 * 種類ごとに、AI への指示(何を要約の主役にするか)や、中身の取り出し方(本文抽出・OCR・文字起こし)を変える。
 */
enum class ContentKind {
    /** Web ページ(記事・ブログ・ドキュメントなど) */
    WEB_PAGE,

    /** 動画(YouTube などの動画ページ、動画ファイル) */
    VIDEO,

    /** 手書きのメモ・共有されたテキスト */
    MEMO,

    /** 画像(写真・スクリーンショット) */
    IMAGE,

    /** 文書(PDF) */
    DOCUMENT,

    /** 音声(録音・ポッドキャストなど) */
    AUDIO
}

/** このブックマークの中身の種類。 */
fun Bookmark.contentKind(): ContentKind = when (type) {
    BookmarkType.TEXT -> ContentKind.MEMO
    BookmarkType.IMAGE -> ContentKind.IMAGE
    BookmarkType.PDF -> ContentKind.DOCUMENT
    BookmarkType.AUDIO -> ContentKind.AUDIO
    BookmarkType.VIDEO -> ContentKind.VIDEO
    BookmarkType.URL -> contentKindOfMimeType(mimeType)
        ?: contentUri?.let { contentKindOfUrl(it) }
        ?: ContentKind.WEB_PAGE
}

/** MIME タイプから種類を決める。HTML・不明なら null。 */
fun contentKindOfMimeType(mimeType: String?): ContentKind? {
    val type = mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return null
    return when {
        type == "application/pdf" -> ContentKind.DOCUMENT
        type.startsWith("image/") -> ContentKind.IMAGE
        type.startsWith("audio/") -> ContentKind.AUDIO
        type.startsWith("video/") -> ContentKind.VIDEO
        type.startsWith("text/plain") -> ContentKind.MEMO
        else -> null
    }
}

/** 動画サイトの URL なら [ContentKind.VIDEO]。それ以外は null(呼び出し側で Web ページ扱い)。 */
fun contentKindOfUrl(url: String): ContentKind? {
    val host = try {
        URI(url.trim()).host?.lowercase()?.removePrefix("www.")?.removePrefix("m.")
    } catch (_: Exception) {
        null
    } ?: return null
    return if (VIDEO_HOSTS.any { host == it || host.endsWith(".$it") }) ContentKind.VIDEO else null
}

/** 動画サイト(動画を見せるのが主なページ)のホスト */
private val VIDEO_HOSTS = listOf(
    "youtube.com", "youtu.be", "vimeo.com", "nicovideo.jp", "nico.ms", "tiktok.com", "twitch.tv", "dailymotion.com"
)
