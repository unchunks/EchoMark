package com.unchunks.echomark.data.mapper

import com.unchunks.echomark.domain.websearch.WebSource
import com.unchunks.echomark.domain.websearch.WebSourceText
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 回答の Web のページ([WebSource])を、DB の列(chat_messages.webSources)とバックアップに入れる JSON の配列にする。
 * 例: `[{"n":3,"url":"https://example.com/a","title":"タイトル","domain":"example.com","excerpt":"抜粋"}]`
 */
object WebSourcesJson {
    /** 空なら null(列に何も入れない) */
    fun encode(sources: List<WebSource>): String? =
        if (sources.isEmpty()) null else toJsonArray(sources).toString()

    fun toJsonArray(sources: List<WebSource>): JSONArray = JSONArray().apply {
        sources.forEach { source ->
            put(
                JSONObject()
                    .put("n", source.number)
                    .put("url", source.url)
                    .put("title", source.title)
                    .put("domain", source.domain)
                    .put("excerpt", source.excerpt)
            )
        }
    }

    /** 読めない・形のおかしいものは除く(http/https 以外の URL・番号の無いもの) */
    fun decode(json: String?): List<WebSource> {
        if (json.isNullOrBlank()) return emptyList()
        val array = try {
            JSONArray(json)
        } catch (e: JSONException) {
            return emptyList()
        }
        return fromJsonArray(array)
    }

    fun fromJsonArray(array: JSONArray): List<WebSource> = (0 until array.length()).mapNotNull { index ->
        val entry = array.optJSONObject(index) ?: return@mapNotNull null
        val number = entry.optInt("n", 0).takeIf { it > 0 } ?: return@mapNotNull null
        val url = entry.optString("url").trim()
        val domain = WebSourceText.domainOf(url) ?: return@mapNotNull null
        WebSource(
            number = number,
            url = url,
            title = entry.optString("title").ifBlank { domain },
            domain = entry.optString("domain").ifBlank { domain },
            excerpt = entry.optString("excerpt")
        )
    }.distinctBy { it.number }
}
