package com.unchunks.echomark.data.ai.local

import com.unchunks.echomark.data.ai.AiPrompts
import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.model.TagNames
import org.json.JSONObject
import timber.log.Timber

/**
 * LLM の出力(JSON を含む文字列)を [BookmarkAnalysis] に変換する。失敗時は入力テキストから代替を作る。
 * タグは [TagNames.clean] で整え、表記ゆれの重複を除く(既存タグへのそろえは保存時に行う)。
 */
object AnalysisParser {
    const val MAX_TAGS = AiPrompts.MAX_TAGS
    const val FALLBACK_SUMMARY_CHARS = 100
    const val DEFAULT_CATEGORY = "未分類"

    fun parse(response: String, source: String): BookmarkAnalysis {
        return try {
            val start = response.indexOf('{')
            val end = response.lastIndexOf('}')
            require(start in 0 until end) { "JSON が見つからない" }
            val json = JSONObject(response.substring(start, end + 1))

            val summary = json.optString("summary").trim()
            val tags = tagCandidates(json)
                .map { TagNames.clean(it) }
                .filter { isUsableTag(it) }
                .distinctBy { TagNames.key(it) }
                .take(MAX_TAGS)
            val category = json.optString("category").trim()
                .takeUnless { PLACEHOLDER_CATEGORY.matches(it) || it.length > MAX_CATEGORY_CHARS }
                .orEmpty()
                .ifEmpty { DEFAULT_CATEGORY }

            BookmarkAnalysis(
                summary = summary.ifEmpty { fallbackSummary(source) },
                tags = tags,
                category = category
            )
        } catch (e: Exception) {
            Timber.w(e, "LLM 出力の JSON 解析に失敗。フォールバックを使用")
            BookmarkAnalysis(
                summary = fallbackSummary(source),
                tags = emptyList(),
                category = DEFAULT_CATEGORY
            )
        }
    }

    /**
     * 部分要約の出力(`{"notes": "..."}`)からメモを取り出す。
     * JSON が壊れていれば notes の値の部分を、それも無ければ出力そのもの(コードブロックの記号を除く)を使う。
     */
    fun parseNotes(response: String): String {
        try {
            val start = response.indexOf('{')
            val end = response.lastIndexOf('}')
            if (start in 0 until end) return JSONObject(response.substring(start, end + 1)).optString("notes").trim()
        } catch (e: Exception) {
            Timber.w(e, "部分要約の JSON 解析に失敗。テキストとして使う")
        }
        NOTES_VALUE.find(response)?.let { return it.groupValues[1].trim() }
        return response.replace("```json", "").replace("```", "").trim()
    }

    /**
     * tags の値をタグ名の候補にする。配列でなく1つの文字列("Android, Kotlin" や "#Android #Kotlin")で返されたときや、
     * 配列の1要素に複数のタグを詰めたときも、区切りで分ける(端末内の小さいモデルは形式を崩しやすい)。
     */
    private fun tagCandidates(json: JSONObject): List<String> {
        val raw = json.optJSONArray("tags")?.let { array -> (0 until array.length()).map { array.optString(it) } }
            ?: json.optString("tags").takeIf { it.isNotBlank() }?.let { listOf(it) }
            ?: return emptyList()
        return raw.flatMap { it.split(TAG_SEPARATORS) }
    }

    /**
     * 保存してよいタグか。出力形式の例(「タグ1」)をそのまま写したもの、文になっている長いもの、
     * どの保存にも付く汎用の語(「動画」「記事」「まとめ」など。絞り込みの役に立たない)は使わない。
     */
    internal fun isUsableTag(tag: String): Boolean {
        if (tag.isEmpty() || tag.length > MAX_TAG_CHARS) return false
        if (tag.any { it in SENTENCE_CHARS }) return false
        if (PLACEHOLDER_TAG.matches(tag)) return false
        return TagNames.key(tag) !in GENERIC_TAG_KEYS
    }

    /**
     * タグの区切り(読点・カンマ、空白の後の「#」)。「CI/CD」「C#」のようにタグ名の中にある記号では分けない
     */
    private val TAG_SEPARATORS = Regex("""[,、，]|\s+(?=[#＃])""")

    /** これより長いタグ名は、文や説明を写したものとみなす */
    private const val MAX_TAG_CHARS = 20

    /** これより長いカテゴリは、1語ではない(説明を書いた)ものとみなす */
    private const val MAX_CATEGORY_CHARS = 12

    private const val SENTENCE_CHARS = "。！？!?「」\n"

    /** 出力形式の例(AiPrompts.analyzeInstructions)を写したタグ */
    private val PLACEHOLDER_TAG = Regex("""(?i)(?:タグ|tag)\s*[0-9０-９]*""")

    /** 出力形式の例を写したカテゴリ */
    private val PLACEHOLDER_CATEGORY = Regex("""カテゴリ名?(?:1つ|１つ)?""")

    /** 中身の種類や保存そのものを表すだけの、どのブックマークにも付く語 */
    private val GENERIC_TAG_KEYS = listOf(
        "動画", "記事", "ブログ", "ページ", "webページ", "ウェブページ", "web", "サイト", "ウェブサイト", "youtube",
        "情報", "まとめ", "解説", "紹介", "説明", "要約", "メモ", "画像", "写真", "文書", "資料", "音声",
        "ブックマーク", "その他", "未分類", "コンテンツ"
    ).map { TagNames.key(it) }.toSet()

    /** 閉じていない JSON(生成の打ち切りなど)から notes の値を取り出す */
    private val NOTES_VALUE = Regex("\"notes\"\\s*:\\s*\"([^\"]*)")

    private fun fallbackSummary(source: String): String = source.trim().take(FALLBACK_SUMMARY_CHARS)
}
