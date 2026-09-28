package com.unchunks.echomark.data.ai.local

import com.unchunks.echomark.domain.model.BookmarkAnalysis
import org.json.JSONObject
import timber.log.Timber

/** LLM の出力(JSON を含む文字列)を [BookmarkAnalysis] に変換する。失敗時は入力テキストから代替を作る。 */
object AnalysisParser {
    const val MAX_TAGS = 5
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
                        val tag = tagsArray.optString(i).trim().removePrefix("#")
                        if (tag.isNotEmpty()) add(tag)
                    }
                }
            }.distinct().take(MAX_TAGS)
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

    private fun fallbackSummary(source: String): String = source.trim().take(FALLBACK_SUMMARY_CHARS)
}
