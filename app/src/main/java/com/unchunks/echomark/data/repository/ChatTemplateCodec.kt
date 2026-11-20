package com.unchunks.echomark.data.repository

import com.unchunks.echomark.domain.model.ChatTemplate
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber

/** チャットのテンプレートと、DataStore に保存する JSON の変換。 */
internal object ChatTemplateCodec {
    fun encode(templates: List<ChatTemplate>): String =
        JSONArray(
            templates.map { JSONObject().put("id", it.id).put("title", it.title).put("text", it.text) }
        ).toString()

    /** 読めない値は空にする(設定全体を読めなくしない)。 */
    fun decode(json: String): List<ChatTemplate> = try {
        val array = JSONArray(json)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            ChatTemplate(item.getString("id"), item.getString("title"), item.getString("text"))
        }
    } catch (e: JSONException) {
        Timber.w(e, "チャットのテンプレートを読めなかった")
        emptyList()
    }
}
