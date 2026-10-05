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
            val tagsArray = json.optJSONArray("tags")
            val tags = buildList {
                if (tagsArray != null) {
                    for (i in 0 until tagsArray.length()) {
                        val tag = TagNames.clean(tagsArray.optString(i))
                        if (tag.isNotEmpty()) add(tag)
                    }
                }
            }.distinctBy { TagNames.key(it) }.take(MAX_TAGS)
            val category = json.optString("category").trim().ifEmpty { DEFAULT_CATEGORY }

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

    /** 閉じていない JSON(生成の打ち切りなど)から notes の値を取り出す */
    private val NOTES_VALUE = Regex("\"notes\"\\s*:\\s*\"([^\"]*)")

    private fun fallbackSummary(source: String): String = source.trim().take(FALLBACK_SUMMARY_CHARS)
}
