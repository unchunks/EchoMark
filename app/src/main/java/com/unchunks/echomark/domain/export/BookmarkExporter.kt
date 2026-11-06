package com.unchunks.echomark.domain.export

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkNote
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 書き出すファイルの形式。 */
enum class ExportFormat(
    val label: String,
    val description: String,
    val extension: String,
    val mimeType: String
) {
    /** 要約・タグ・ノートまで入る、読み物としての書き出し */
    MARKDOWN("Markdown", "要約・タグ・ノートも入ります。メモアプリや GitHub などで読めます", "md", "text/markdown"),

    /** ブラウザの「ブックマークをインポート」や、他のブックマークサービスで読める形式(Netscape 形式) */
    BROWSER_HTML("ブラウザのブックマーク(HTML)", "Chrome・Firefox・Safari などや、他のサービスに取り込めます。リンクだけが入ります", "html", "text/html")
}

/** 書き出す1件のブックマークと、そのノート。 */
data class ExportItem(val bookmark: Bookmark, val notes: List<BookmarkNote> = emptyList())

/**
 * 書き出した文章。[exported] 件を書き、[skipped] 件は形式に入れられず飛ばした
 * (ブラウザのブックマークは、リンクの無いメモ・ファイルを入れられない)。
 */
data class ExportDocument(val text: String, val exported: Int, val skipped: Int)

/** 書き出しに失敗した。[message] は画面に出せる文言。 */
class ExportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * ブックマークをファイルに書き出す文章を作る純粋な処理(#91)。ブックマーク1件でも、コレクション全体でも使う。
 * ファイルへの書き込みは data 層でする。
 */
object BookmarkExporter {

    /** 保存先の候補にする、ファイル名の最大の長さ(拡張子を除く) */
    private const val MAX_FILE_NAME_CHARS = 60

    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/M/d")

    /**
     * [title](コレクションの名前、ブックマーク1件ならそのタイトル)を文書の見出しにして、[items] を [format] の文章にする。
     * [description] はコレクションの説明(Markdown では見出しの下に入れる)。[exportedAt] は書き出した日時。
     */
    fun render(
        format: ExportFormat,
        title: String,
        description: String?,
        items: List<ExportItem>,
        exportedAt: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): ExportDocument = when (format) {
        ExportFormat.MARKDOWN -> markdown(title, description, items, exportedAt, zone)
        ExportFormat.BROWSER_HTML -> browserHtml(title, items)
    }

    /** 保存先の初期のファイル名。ファイル名に使えない文字は「_」にし、長すぎれば切る。空なら「EchoMark」 */
    fun fileName(title: String, format: ExportFormat): String {
        val cleaned = title.map { if (it.isISOControl() || it in "\\/:*?\"<>|") '_' else it }
            .joinToString("")
            .trim().trim('.').take(MAX_FILE_NAME_CHARS).trim()
        return "${cleaned.ifEmpty { "EchoMark" }}.${format.extension}"
    }

    // ---- Markdown ----

    private fun markdown(title: String, description: String?, items: List<ExportItem>, exportedAt: Long, zone: ZoneId): ExportDocument {
        val out = StringBuilder()
        out.append("# ").append(escapeMarkdown(title)).append("\n\n")
        description?.takeIf { it.isNotBlank() }?.let { out.append(it.trim()).append("\n\n") }
        out.append("> ${formatDate(exportedAt, zone)} に EchoMark から書き出し(${items.size}件)\n")
        items.forEach { item ->
            out.append("\n").append(markdownItem(item, zone))
        }
        return ExportDocument(out.toString(), exported = items.size, skipped = 0)
    }

    private fun markdownItem(item: ExportItem, zone: ZoneId): String {
        val bookmark = item.bookmark
        val out = StringBuilder()
        val url = bookmark.webUrl()
        out.append("## ")
        if (url != null) {
            out.append('[').append(escapeMarkdown(bookmark.title)).append("](").append(escapeMarkdownUrl(url)).append(")")
        } else {
            out.append(escapeMarkdown(bookmark.title))
        }
        out.append("\n\n")

        val facts = listOfNotNull(
            "${formatDate(bookmark.createdAt, zone)} に保存",
            bookmark.siteName?.takeIf { it.isNotBlank() },
            bookmark.category?.takeIf { it.isNotBlank() },
            bookmark.fileName?.takeIf { bookmark.filePath != null && it.isNotBlank() }?.let { "ファイル: $it" },
            if (bookmark.isFavorite) "お気に入り" else null
        )
        out.append(facts.joinToString(" · ") { escapeMarkdown(it) }).append("\n\n")

        if (bookmark.tags.isNotEmpty()) {
            out.append(bookmark.tags.joinToString(" ") { "#" + it.replace(Regex("\\s+"), "_") }).append("\n\n")
        }
        bookmark.summary?.takeIf { it.isNotBlank() }?.let { out.append(it.trim()).append("\n\n") }
        // 自分で書いたテキストのブックマークは、本文がそのまま中身。リンク先の本文(記事の全文)は書き出さない
        if (bookmark.type == BookmarkType.TEXT) {
            bookmark.content?.takeIf { it.isNotBlank() }?.let { out.append(it.trim()).append("\n\n") }
        }
        if (item.notes.isNotEmpty()) {
            out.append("### ノート\n\n")
            item.notes.forEach { note ->
                note.quote?.let { quote -> out.append(quote.trim().lines().joinToString("\n") { "> $it" }).append("\n\n") }
                out.append(note.text.trim()).append("\n\n")
            }
        }
        return out.toString().trimEnd() + "\n"
    }

    /** Markdown の記号を文字として出す(見出し・リンクの文字が崩れないように) */
    internal fun escapeMarkdown(text: String): String =
        text.replace(Regex("\\s*[\r\n]+\\s*"), " ").replace(Regex("""([\\`*_\[\]<>#|])"""), """\\$1""").trim()

    /** リンクの URL に含まれる空白と括弧を、リンクが途切れないように符号にする */
    internal fun escapeMarkdownUrl(url: String): String =
        url.replace(" ", "%20").replace("(", "%28").replace(")", "%29").replace("<", "%3C").replace(">", "%3E")

    // ---- ブラウザのブックマーク(Netscape 形式) ----

    private fun browserHtml(title: String, items: List<ExportItem>): ExportDocument {
        val linked = items.filter { it.bookmark.webUrl() != null }
        val out = StringBuilder()
        out.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
        out.append("<!-- This is an automatically generated file. It will be read and overwritten. DO NOT EDIT! -->\n")
        out.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        out.append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n")
        out.append("    <DT><H3>").append(escapeHtml(title)).append("</H3>\n    <DL><p>\n")
        linked.forEach { item ->
            val bookmark = item.bookmark
            out.append("        <DT><A HREF=\"").append(escapeHtml(bookmark.webUrl().orEmpty())).append('"')
            out.append(" ADD_DATE=\"").append(bookmark.createdAt / 1000).append('"')
            if (bookmark.tags.isNotEmpty()) {
                // タグの区切りはコンマなので、名前に含まれるコンマは空白にする
                out.append(" TAGS=\"").append(escapeHtml(bookmark.tags.joinToString(",") { it.replace(',', ' ').trim() })).append('"')
            }
            out.append('>').append(escapeHtml(bookmark.title)).append("</A>\n")
            bookmark.summary?.takeIf { it.isNotBlank() }?.let {
                out.append("        <DD>").append(escapeHtml(it.trim().replace(Regex("\\s*[\r\n]+\\s*"), " "))).append('\n')
            }
        }
        out.append("    </DL><p>\n</DL><p>\n")
        return ExportDocument(out.toString(), exported = linked.size, skipped = items.size - linked.size)
    }

    internal fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun Bookmark.webUrl(): String? =
        contentUri?.trim()?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }

    private fun formatDate(millis: Long, zone: ZoneId): String = DATE_FORMAT.format(Instant.ofEpochMilli(millis).atZone(zone))
}

/** ファイルへの書き出しの結果。[exported] 件を書き、[skipped] 件は形式に入れられず飛ばした。 */
data class ExportResult(val exported: Int, val skipped: Int)
