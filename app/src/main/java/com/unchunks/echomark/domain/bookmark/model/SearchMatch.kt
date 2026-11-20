package com.unchunks.echomark.domain.bookmark.model

import java.text.Normalizer

/** キーワード検索で一致を探す場所。DAO の `searchByKeyword` が見る列と同じ。 */
enum class MatchField(val label: String) {
    TITLE("タイトル"),
    SUMMARY("要約"),
    CONTENT("本文"),
    FILE_NAME("ファイル名"),
    URL("URL"),
    SITE_NAME("サイト名"),
    TAG("タグ")
}

/**
 * 本文のうち、一致した場所の前後を切り出したもの。
 * @property text 表示する文字列。前後が切れていれば「…」が付く。改行は空白にしてある
 * @property ranges [text] の中で検索語に一致する範囲(終端を含む。重なりは1つにまとめてある)
 */
data class MatchSnippet(val text: String, val ranges: List<IntRange>)

/**
 * 1件のブックマークが検索語に一致した理由(#54)。
 * @property fields 一致した場所
 * @property tags 一致したタグの名前(タグに一致したときだけ)
 * @property snippet 本文に一致した場所の前後。タイトルと要約だけでは見えない語が本文にあるときだけ付く
 */
data class SearchMatch(
    val fields: List<MatchField> = emptyList(),
    val tags: List<String> = emptyList(),
    val snippet: MatchSnippet? = null
) {
    /** キーワードには一致せず、意味の近さだけで見つかったか */
    val isSemanticOnly: Boolean get() = fields.isEmpty()

    /** 「タイトル・本文・タグ「kotlin」」のような、一致した場所の言い方 */
    val reason: String
        get() = fields.joinToString("・") { field ->
            if (field == MatchField.TAG && tags.isNotEmpty()) "タグ「${tags.joinToString("」「")}」" else field.label
        }
}

/**
 * 検索語がどこに一致したかを調べ、一致した範囲を返す。純粋関数(JVM 単体テスト可能)。
 *
 * DB の検索(LIKE)は大文字小文字を区別せず、全角英数字は半角にそろえた語でも探す。ここでも同じ見方をするため、
 * 文字を NFKC でそろえて小文字にしてから比べ、見つけた範囲は元の文字列の位置に戻して返す。
 */
object SearchMatcher {
    /** 1つの文字列で集める一致の範囲の最大数(長い本文で語が何千回も出ても、強調の数を抑える) */
    const val MAX_RANGES = 200

    /** 本文の切り出しで、一致の前後に残す文字数 */
    const val SNIPPET_RADIUS = 36

    /**
     * [bookmark] が [terms](空白で区切った検索語)に一致した場所と、本文の切り出しを返す。
     * どの場所にも一致しなければ、[SearchMatch.fields] は空(意味の近さだけで見つかったもの)。
     */
    fun explain(bookmark: Bookmark, terms: List<String>): SearchMatch {
        val folded = terms.map { fold(it).text }.filter { it.isNotEmpty() }
        if (folded.isEmpty()) return SearchMatch()

        val title = FoldedText(bookmark.title)
        val summary = bookmark.summary?.let { FoldedText(it) }
        val content = bookmark.content?.let { FoldedText(it) }
        val fileName = bookmark.fileName?.let { FoldedText(it) }
        val url = bookmark.contentUri?.let { FoldedText(it) }
        val siteName = bookmark.siteName?.let { FoldedText(it) }
        val matchedTags = bookmark.tags.filter { tag ->
            val tagText = FoldedText(tag)
            folded.any { tagText.contains(it) }
        }

        val fields = buildList {
            if (title.containsAny(folded)) add(MatchField.TITLE)
            if (summary?.containsAny(folded) == true) add(MatchField.SUMMARY)
            if (content?.containsAny(folded) == true) add(MatchField.CONTENT)
            if (fileName?.containsAny(folded) == true) add(MatchField.FILE_NAME)
            if (url?.containsAny(folded) == true) add(MatchField.URL)
            if (siteName?.containsAny(folded) == true) add(MatchField.SITE_NAME)
            if (matchedTags.isNotEmpty()) add(MatchField.TAG)
        }

        // タイトルと要約にすでに出ている語は、その場で強調して見える。見えない語が本文にあるときだけ、本文の一節を出す
        val hiddenTerm = content?.let { body ->
            folded.firstOrNull { term -> !title.contains(term) && summary?.contains(term) != true && body.contains(term) }
        }
        val snippet = if (content != null && hiddenTerm != null) snippetAround(content, hiddenTerm, folded) else null
        return SearchMatch(fields = fields, tags = matchedTags, snippet = snippet)
    }

    /** [text] の中で [terms] に一致する範囲(終端を含む)を、前から順に返す。重なる範囲は1つにまとめる。 */
    fun ranges(text: String, terms: List<String>): List<IntRange> {
        val folded = terms.map { fold(it).text }.filter { it.isNotEmpty() }
        if (folded.isEmpty() || text.isEmpty()) return emptyList()
        return FoldedText(text).ranges(folded)
    }

    private fun snippetAround(content: FoldedText, term: String, allTerms: List<String>): MatchSnippet? {
        val hit = content.rangeOf(term) ?: return null
        val original = content.original
        var start = (hit.first - SNIPPET_RADIUS).coerceAtLeast(0)
        var end = (hit.last + 1 + SNIPPET_RADIUS).coerceAtMost(original.length)
        // サロゲートペアの途中で切らない
        if (start in 1 until original.length && Character.isLowSurrogate(original[start])) start++
        if (end in 1 until original.length && Character.isLowSurrogate(original[end])) end--
        val body = original.substring(start, end).replace('\n', ' ').replace('\r', ' ')
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < original.length) "…" else ""
        val shifted = ranges(body, allTerms).map { (it.first + prefix.length)..(it.last + prefix.length) }
        return MatchSnippet(prefix + body + suffix, shifted)
    }

    /** NFKC でそろえて小文字にした文字列と、その各文字が元の文字列のどこから来たか */
    private class FoldedText(val original: String) {
        val text: String
        private val origin: IntArray

        init {
            val folded = fold(original)
            text = folded.text
            origin = folded.origin
        }

        fun contains(term: String): Boolean = text.contains(term)

        fun containsAny(terms: List<String>): Boolean = terms.any { text.contains(it) }

        /** 最初の一致を、元の文字列の範囲(終端を含む)で返す */
        fun rangeOf(term: String): IntRange? {
            val index = text.indexOf(term)
            return if (index < 0) null else toOriginal(index, index + term.length)
        }

        fun ranges(terms: List<String>): List<IntRange> {
            val found = ArrayList<IntRange>()
            for (term in terms) {
                var from = 0
                while (found.size < MAX_RANGES) {
                    val index = text.indexOf(term, from)
                    if (index < 0) break
                    found += toOriginal(index, index + term.length)
                    from = index + term.length
                }
            }
            return merge(found)
        }

        private fun toOriginal(foldedStart: Int, foldedEnd: Int): IntRange {
            val first = origin[foldedStart]
            val lastStart = origin[foldedEnd - 1]
            val last = lastStart + Character.charCount(original.codePointAt(lastStart)) - 1
            return first..last
        }

        private fun merge(ranges: List<IntRange>): List<IntRange> {
            if (ranges.size < 2) return ranges
            val merged = ArrayList<IntRange>()
            for (range in ranges.sortedBy { it.first }) {
                val last = merged.lastOrNull()
                if (last != null && range.first <= last.last + 1) {
                    if (range.last > last.last) merged[merged.lastIndex] = last.first..range.last
                } else {
                    merged += range
                }
            }
            return merged
        }
    }

    private class Folded(val text: String, val origin: IntArray)

    private fun fold(source: String): Folded {
        val out = StringBuilder(source.length)
        var origin = IntArray(source.length.coerceAtLeast(1))
        var size = 0
        fun append(char: Char, from: Int) {
            if (size == origin.size) origin = origin.copyOf(origin.size * 2)
            origin[size++] = from
            out.append(char)
        }
        var i = 0
        while (i < source.length) {
            val codePoint = source.codePointAt(i)
            val width = Character.charCount(codePoint)
            if (width == 1 && isUnchangedByNfkc(codePoint)) {
                append(source[i].lowercaseChar(), i)
            } else {
                val folded = Normalizer.normalize(source.substring(i, i + width), Normalizer.Form.NFKC).lowercase()
                for (char in folded) append(char, i)
            }
            i += width
        }
        return Folded(out.toString(), origin.copyOf(size))
    }

    /** NFKC で変わらない文字(英数字の半角・ひらがな・カタカナ・漢字)。長い本文を速く処理するための近道 */
    private fun isUnchangedByNfkc(codePoint: Int): Boolean =
        codePoint < 0x80 || codePoint in 0x3041..0x309A || codePoint in 0x30A1..0x30FE || codePoint in 0x4E00..0x9FFF
}
