package com.unchunks.echomark.domain.podcast

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.bookmark.model.EvidenceFinder
import com.unchunks.echomark.domain.bookmark.model.contentKind
import com.unchunks.echomark.domain.websearch.WebSourceText

/**
 * 台本の決まった部分(始まり・出典の紹介・終わり)と、AI を使えないときの要約の読み上げ(純粋関数)。
 * 出典はここで必ず入れる(AI の書いた紹介に出典が抜けても、何の紹介か分かるように)。
 */
object PodcastTemplates {
    /** 1回のポッドキャストにまとめられるブックマークの数 */
    const val MAX_SOURCES = 10

    /** 話すタイトルの上限 */
    private const val MAX_TITLE_CHARS = 60

    /** 要約の読み上げで読む行の上限(1件あたり) */
    private const val MAX_FALLBACK_LINES = 8

    /** 要約が無いときに読む本文の先頭の文字数 */
    private const val FALLBACK_EXCERPT_CHARS = 300

    /** 紹介する順([index] は 1 始まり)のブックマークを、台本の材料にする */
    fun sourceOf(bookmark: Bookmark, index: Int, excerptChars: Int): PodcastSource {
        val url = bookmark.contentUri?.takeIf { bookmark.type == BookmarkType.URL }
        val sourceName = bookmark.siteName?.trim()?.takeIf { it.isNotEmpty() } ?: url?.let(WebSourceText::domainOf)
        return PodcastSource(
            bookmarkId = bookmark.id,
            index = index,
            title = spokenTitle(bookmark.title, sourceName),
            kindLabel = kindLabel(bookmark),
            sourceName = sourceName,
            url = url,
            summary = bookmark.summary?.trim()?.takeIf { it.isNotEmpty() },
            excerpt = bookmark.content?.trim()?.takeIf { it.isNotEmpty() }?.take(excerptChars)
        )
    }

    /** エピソードの題名。1件ならそのタイトル、複数なら「最初のタイトル ほかn件」 */
    fun episodeTitle(sources: List<PodcastSource>): String = when (sources.size) {
        0 -> "ポッドキャスト"
        1 -> sources.single().title
        else -> "${sources.first().title} ほか${sources.size - 1}件"
    }

    fun opening(format: PodcastFormat, sources: List<PodcastSource>): List<ScriptLine> {
        val topic = if (sources.size == 1) "保存したブックマークを1件紹介します。" else "保存したブックマークから${sources.size}件を紹介します。"
        return when (format) {
            PodcastFormat.SOLO -> listOf(ScriptLine(PodcastSpeaker.HOST, "EchoMark ポッドキャストです。$topic"))
            PodcastFormat.DUO -> listOf(
                ScriptLine(PodcastSpeaker.HOST, "こんにちは。EchoMark ポッドキャストです。今回は、$topic"),
                ScriptLine(PodcastSpeaker.GUEST, "よろしくお願いします。")
            )
        }
    }

    /** 出典の紹介。「2件目は、Zenn の記事「…」です。」 */
    fun attribution(source: PodcastSource, total: Int): ScriptLine {
        val lead = if (total == 1) "紹介するのは、" else "${source.index}件目は、"
        val origin = source.sourceName?.let { "${it}の" } ?: if (source.url == null) "保存した" else ""
        return ScriptLine(PodcastSpeaker.HOST, "$lead$origin${source.kindLabel}「${source.title}」です。", source.index)
    }

    fun closing(format: PodcastFormat, sources: List<PodcastSource>): List<ScriptLine> {
        val names = sources.mapNotNull { it.sourceName }.distinct()
        val credit = if (names.isEmpty()) "" else "出典は、${names.joinToString("、")}でした。"
        val host = ScriptLine(PodcastSpeaker.HOST, "${credit}詳しくは、EchoMark で元のブックマークを開いてみてください。お聞きいただき、ありがとうございました。")
        return when (format) {
            PodcastFormat.SOLO -> listOf(host)
            PodcastFormat.DUO -> listOf(host, ScriptLine(PodcastSpeaker.GUEST, "ありがとうございました。"))
        }
    }

    /** AI を使えないときの紹介。要約(無ければ本文の先頭)の文を、そのまま語り手が読む */
    fun summaryRead(source: PodcastSource): List<ScriptLine> {
        val text = source.summary ?: source.excerpt?.take(FALLBACK_EXCERPT_CHARS)
        val sentences = text?.let(EvidenceFinder::splitSentences)
            ?.map { it.trim().trimStart('-', '*', '・', '•', '#').trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
            .take(MAX_FALLBACK_LINES)
        if (sentences.isEmpty()) return listOf(ScriptLine(PodcastSpeaker.HOST, "このブックマークには、まだ要約がありません。", source.index))
        return sentences.map { ScriptLine(PodcastSpeaker.HOST, it, source.index) }
    }

    /** 「記事」「動画」などの言い方 */
    fun kindLabel(bookmark: Bookmark): String = when (bookmark.contentKind()) {
        ContentKind.WEB_PAGE -> "記事"
        ContentKind.VIDEO -> "動画"
        ContentKind.MEMO -> "メモ"
        ContentKind.IMAGE -> "画像"
        ContentKind.DOCUMENT -> "文書"
        ContentKind.AUDIO -> "音声"
    }

    /** 話すタイトル。後ろのサイト名(`… | Zenn`・`… - Qiita`)を除き、長ければ切る */
    fun spokenTitle(title: String, sourceName: String?): String {
        var t = title.replace(Regex("""\s+"""), " ").trim()
        if (sourceName != null) {
            t = t.replace(Regex("""\s*[|｜\-–—:]\s*${Regex.escape(sourceName)}\s*$""", RegexOption.IGNORE_CASE), "").trim()
        }
        if (t.isEmpty()) t = title.trim()
        return if (t.length <= MAX_TITLE_CHARS) t else t.take(MAX_TITLE_CHARS - 1).trimEnd() + "…"
    }
}
