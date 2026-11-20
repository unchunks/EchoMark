package com.unchunks.echomark.data.repository

import com.unchunks.echomark.domain.podcast.ReadingEntry
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber

/** 読みの辞書と、DataStore に保存する JSON の変換。例: `[{"surface":"EchoMark","reading":"エコーマーク"}]` */
internal object ReadingDictionaryCodec {
    fun encode(entries: List<ReadingEntry>): String =
        JSONArray(entries.map { JSONObject().put("surface", it.surface).put("reading", it.reading) }).toString()

    /** 読めない値は空にし、誤りのある1件は除く(設定全体を読めなくしない)。 */
    fun decode(json: String): List<ReadingEntry> = try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val surface = item.optString("surface")
            val reading = item.optString("reading")
            if (ReadingEntry.problemOf(surface, reading) == null) ReadingEntry(surface, reading) else null
        }
    } catch (e: JSONException) {
        Timber.w(e, "読みの辞書を読めなかった")
        emptyList()
    }
}
