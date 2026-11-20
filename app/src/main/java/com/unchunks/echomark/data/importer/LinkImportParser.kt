package com.unchunks.echomark.data.importer

import com.unchunks.echomark.domain.importer.ImportFormat
import com.unchunks.echomark.domain.importer.ImportParseResult
import com.unchunks.echomark.domain.importer.ImportedLink
import com.unchunks.echomark.domain.importer.MAX_IMPORT_LINKS
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * 他のサービスの書き出しファイルの中身(文字列)から、取り込むリンクを読み取る。ファイルを読むだけで、通信やアカウントは使わない。
 *
 * 対応する形式(形は中身で見分ける。ファイル名には頼らない):
 * - **HTML**: ブラウザ(Chrome・Firefox・Safari・Edge)・Raindrop.io・Pinboard・Instapaper の Netscape 形式のブックマーク
 *   (`<DT><A HREF ADD_DATE TAGS>`、フォルダは `<H3>` と入れ子の `<DL>`、説明は `<DD>`)と、Pocket の書き出し
 *   (`<a href time_added tags>` が「Unread」「Read Archive」の見出しの下に並ぶ)
 * - **CSV**: 1行目の見出しで列を見分ける(Raindrop.io・Pocket・Instapaper など。url / title / note / tags / folder / created / favorite / status)
 * - **JSON**: Pinboard の書き出し(`href`・`description`(タイトル)・`extended`(メモ)・`tags`(空白区切り)・`time`)や、
 *   url・title を持つ要素の配列
 *
 * 認識できない形式は null。URL は http / https だけを取り込み、同じ URL は1件にまとめる(タグは足し合わせる)。
 */
object LinkImportParser {

    fun parse(text: String): ImportParseResult? {
        val body = text.removePrefix(BYTE_ORDER_MARK.toString()).trimStart()
        if (body.isEmpty()) return null
        return when {
            body.startsWith("[") || body.startsWith("{") -> parseJson(body)
            looksLikeHtml(body) -> parseHtml(body)
            else -> parseCsv(body)
        }
    }

    private fun looksLikeHtml(body: String): Boolean {
        val head = body.take(HTML_SNIFF_CHARS).lowercase(Locale.ROOT)
        return head.startsWith("<") || head.contains("<a ") || head.contains("netscape-bookmark-file")
    }

    // ---- HTML ----

    private fun parseHtml(html: String): ImportParseResult? {
        val collector = LinkCollector()
        val document = Jsoup.parse(html)
        // 入れ子の <DL> の深さでフォルダを追う。見出し <H3> は、直後の <DL> の名前になる(入れ子の形が崩れていても、文書の順序で決まる)
        val folders = ArrayDeque<String?>()
        var pendingFolder: String? = null
        var archivedSection = false
        var lastLink: Element? = null
        var lastIndex = -1
        val notes = mutableMapOf<Int, String>()

        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                val element = node as? Element ?: return
                when (element.normalName()) {
                    "dl" -> {
                        folders.addLast(pendingFolder)
                        pendingFolder = null
                    }
                    "h3" -> pendingFolder = element.text().trim().ifEmpty { null }
                    "h1", "h2" -> archivedSection = isArchiveName(element.text())
                    "a" -> {
                        val href = element.attr("href")
                        if (href.isBlank()) return
                        val tags = splitTags(element.attr("tags"))
                        val time = parseTime(element.attr("add_date")) ?: parseTime(element.attr("time_added"))
                        val folder = folders.lastOrNull { !it.isNullOrBlank() }?.takeUnless { isGenericFolder(it) }
                        val added = collector.add(
                            url = href,
                            title = element.text(),
                            note = null,
                            tags = tags,
                            folder = folder,
                            savedAt = time,
                            favorite = false,
                            archived = archivedSection
                        )
                        lastLink = element
                        lastIndex = added
                    }
                    "dd" -> if (lastLink != null && lastIndex >= 0) {
                        val text = element.text().trim()
                        if (text.isNotEmpty()) notes[lastIndex] = text
                    }
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.normalName() == "dl" && folders.isNotEmpty()) folders.removeLast()
            }
        }, document)

        val result = collector.build(ImportFormat.HTML)
        if (notes.isEmpty()) return result
        // <DD> の説明は、その直前のリンクのメモにする
        return result.copy(links = result.links.mapIndexed { index, link -> notes[index]?.let { link.copy(note = it) } ?: link })
    }

    // ---- CSV ----

    private fun parseCsv(text: String): ImportParseResult? {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val delimiter = if (firstLine.count { it == '\t' } > firstLine.count { it == ',' }) '\t' else ','
        val rows = readCsv(text, delimiter)
        val header = rows.firstOrNull()?.map { it.trim().lowercase(Locale.ROOT) } ?: return null
        val urlColumn = header.indexOfFirst { it in URL_KEYS }
        if (urlColumn < 0) return null
        fun column(keys: List<String>): Int = keys.firstNotNullOfOrNull { key -> header.indexOf(key).takeIf { it >= 0 } } ?: -1

        val title = column(TITLE_KEYS)
        val note = column(NOTE_KEYS)
        val tags = column(TAG_KEYS)
        val folder = column(FOLDER_KEYS)
        val time = column(TIME_KEYS)
        val favorite = column(FAVORITE_KEYS)
        val status = column(STATUS_KEYS)

        val collector = LinkCollector()
        for (row in rows.drop(1)) {
            fun cell(index: Int): String? = row.getOrNull(index)?.trim()?.takeIf { index >= 0 && it.isNotEmpty() }
            // 「Folder」が Unread / Archive の書き出し(Instapaper)は、フォルダではなく状態として読む
            val folderValue = cell(folder)
            val folderState = folderValue?.lowercase(Locale.ROOT)
            val archivedByFolder = folderState == "archive" || folderState == "archived"
            val realFolder = folderValue?.takeUnless { folderState == "unread" || archivedByFolder || isGenericFolder(it) }
            val statusValue = cell(status)?.lowercase(Locale.ROOT)
            collector.add(
                url = cell(urlColumn).orEmpty(),
                title = cell(title),
                note = cell(note),
                tags = splitTags(cell(tags)),
                folder = realFolder,
                savedAt = parseTime(cell(time)),
                favorite = parseBool(cell(favorite)),
                archived = archivedByFolder || statusValue == "archive" || statusValue == "archived" || statusValue == "read"
            )
        }
        return collector.build(ImportFormat.CSV)
    }

    // ---- JSON ----

    private fun parseJson(text: String): ImportParseResult? {
        val root = try {
            JSONTokener(text).nextValue()
        } catch (e: JSONException) {
            return null
        }
        val array: JSONArray = when (root) {
            is JSONArray -> root
            is JSONObject -> JSON_LIST_KEYS.firstNotNullOfOrNull { root.optJSONArray(it) } ?: return null
            else -> return null
        }
        val collector = LinkCollector()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            // Pinboard は description がタイトル、extended がメモ
            val pinboardStyle = item.has("href")
            val url = URL_KEYS.firstNotNullOfOrNull { key -> item.optString(key, "").takeIf { it.isNotBlank() } }.orEmpty()
            val title = if (pinboardStyle) item.optString("description", "") else TITLE_KEYS.firstNotNullOfOrNull { item.optString(it, "").takeIf { v -> v.isNotBlank() } }
            val note = (if (pinboardStyle) item.optString("extended", "") else NOTE_KEYS.firstNotNullOfOrNull { item.optString(it, "").takeIf { v -> v.isNotBlank() } }).orEmpty()
            val tags = item.opt("tags").let { value ->
                when (value) {
                    is JSONArray -> (0 until value.length()).map { value.optString(it) }
                    is String -> splitTags(value, spaceSeparated = pinboardStyle)
                    else -> emptyList()
                }
            }
            collector.add(
                url = url,
                title = title?.takeIf { it.isNotBlank() },
                note = note.takeIf { it.isNotBlank() },
                tags = tags,
                folder = null,
                savedAt = TIME_KEYS.firstNotNullOfOrNull { key -> parseTime(item.optString(key, "")) },
                favorite = FAVORITE_KEYS.any { parseBool(item.optString(it, "")) },
                archived = false
            )
        }
        return collector.build(ImportFormat.JSON)
    }

    // ---- 共通 ----

    /** リンクを集める。URL の検証・重複のまとめ・タグの整え・件数の上限をここで行う。 */
    private class LinkCollector {
        private val byUrl = LinkedHashMap<String, ImportedLink>()
        private var invalid = 0
        private var truncated = false

        /** 追加した(または既にあった同じ URL の)リンクの位置。URL が不正なら -1。 */
        fun add(
            url: String,
            title: String?,
            note: String?,
            tags: List<String>,
            folder: String?,
            savedAt: Long?,
            favorite: Boolean,
            archived: Boolean
        ): Int {
            val normalized = normalizeImportUrl(url)
            if (normalized == null) {
                invalid++
                return -1
            }
            val cleanTags = cleanTags(tags)
            val existing = byUrl[normalized]
            if (existing != null) {
                // 同じ URL は1件にまとめ、タグを足し合わせる(お気に入りは、どれかがそうなら)
                byUrl[normalized] = existing.copy(
                    tags = (existing.tags + cleanTags).distinct().take(MAX_TAGS_PER_LINK),
                    favorite = existing.favorite || favorite
                )
                return byUrl.keys.indexOf(normalized)
            }
            if (byUrl.size >= MAX_IMPORT_LINKS) {
                truncated = true
                return -1
            }
            byUrl[normalized] = ImportedLink(
                url = normalized,
                title = title?.trim()?.takeIf { it.isNotEmpty() && it != normalized }?.take(MAX_TITLE_CHARS),
                note = note?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NOTE_CHARS),
                tags = cleanTags,
                folder = folder?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TAG_CHARS),
                savedAt = savedAt,
                favorite = favorite,
                archived = archived
            )
            return byUrl.size - 1
        }

        fun build(format: ImportFormat) =
            ImportParseResult(format, byUrl.values.toList(), invalidCount = invalid, truncated = truncated)
    }

    private const val HTML_SNIFF_CHARS = 2048
    private const val MAX_TAGS_PER_LINK = 10
    private const val MAX_TAG_CHARS = 40
    private const val MAX_TITLE_CHARS = 300
    private const val MAX_NOTE_CHARS = 2000
    private const val MAX_URL_CHARS = 2000

    private val URL_KEYS = listOf("url", "link", "href", "address", "uri")
    private val TITLE_KEYS = listOf("title", "name", "page title")
    private val NOTE_KEYS = listOf("note", "notes", "selection", "comment", "extended", "description")
    private val TAG_KEYS = listOf("tags", "tag", "labels", "label")
    private val FOLDER_KEYS = listOf("folder", "collection", "category", "list")
    private val TIME_KEYS = listOf("created", "time_added", "timestamp", "date_added", "added", "created_at", "date", "saved", "time")
    private val FAVORITE_KEYS = listOf("favorite", "favourite", "starred", "star")
    private val STATUS_KEYS = listOf("status", "state")
    private val JSON_LIST_KEYS = listOf("bookmarks", "items", "posts", "links", "entries")

    /** ブラウザが自動で作るフォルダ(取り込んだ元の整理には使わない) */
    private val GENERIC_FOLDERS = setOf(
        "bookmarks", "bookmarks bar", "bookmarks toolbar", "bookmarks menu", "other bookmarks", "mobile bookmarks",
        "favorites", "favorites bar", "unfiled bookmarks", "reading list",
        "ブックマーク", "ブックマーク バー", "ブックマークバー", "ブックマーク ツールバー", "ブックマークツールバー",
        "ブックマーク メニュー", "その他のブックマーク", "モバイルのブックマーク", "モバイル ブックマーク",
        "お気に入り", "お気に入りバー", "リーディングリスト", "未整理のブックマーク"
    )

    internal fun isGenericFolder(name: String): Boolean = name.trim().lowercase(Locale.ROOT) in GENERIC_FOLDERS

    private fun isArchiveName(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return lower.contains("archive") || lower.contains("アーカイブ")
    }

    /** http / https の URL だけ取り込む。前後の空白を除く。長すぎるもの・空白を含むものは取り込まない。 */
    internal fun normalizeImportUrl(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty() || url.length > MAX_URL_CHARS || url.any { it.isWhitespace() }) return null
        val lower = url.lowercase(Locale.ROOT)
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return null
        // スキームの後にホストが無いもの(「https://」だけ)
        return url.takeIf { it.substringAfter("://").isNotEmpty() }
    }

    /** タグの文字列を分ける。カンマ・「|」・「;」で区切る(タグの中の空白は残す)。[spaceSeparated] なら空白でも区切る(Pinboard)。 */
    internal fun splitTags(raw: String?, spaceSeparated: Boolean = false): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val pattern = if (spaceSeparated) Regex("[,;|\\s]+") else Regex("[,;|]")
        return raw.split(pattern)
    }

    /** タグを整える(先頭の「#」と前後の空白を除き、長すぎるものは切る。重複と空は除く。1件あたり [MAX_TAGS_PER_LINK] 個まで) */
    internal fun cleanTags(tags: List<String>): List<String> =
        tags.map { it.trim().removePrefix("#").trim().take(MAX_TAG_CHARS) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_TAGS_PER_LINK)

    /**
     * 日時の文字列をエポックミリ秒にする。数字だけなら秒・ミリ秒・マイクロ秒を桁数で見分け、
     * ISO 8601(「2023-04-05T12:34:56Z」など)と「2023-04-05 12:34:56」(UTC とみなす)も読む。読めなければ null。
     */
    internal fun parseTime(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { number ->
            if (number <= 0) return null
            return when {
                number >= 100_000_000_000_000L -> number / 1_000 // マイクロ秒(Firefox)
                number >= 100_000_000_000L -> number // ミリ秒
                else -> number * 1_000 // 秒
            }
        }
        return try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            try {
                Instant.parse(text).toEpochMilli()
            } catch (e: DateTimeParseException) {
                try {
                    LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli()
                } catch (e: DateTimeParseException) {
                    null
                }
            }
        }
    }

    /** 「true」「yes」「1」「star」などを真とみなす(空や読めないものは偽)。 */
    internal fun parseBool(raw: String?): Boolean =
        when (raw?.trim()?.lowercase(Locale.ROOT)) {
            "true", "yes", "y", "1", "star", "starred", "favorite", "favourite" -> true
            else -> false
        }
}
