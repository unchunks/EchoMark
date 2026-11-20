package com.unchunks.echomark.data.ai

import com.unchunks.echomark.domain.model.AnalysisScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * 要約・タグ・カテゴリ(と長い本文の部分要約のメモ)の出力の JSON スキーマ。
 * クラウド API の構造化出力と、端末内 AI(LiteRT-LM)の制約付きの生成に渡し、出力の形を決める。
 * 形は指示文([AiPrompts])の出力形式と同じ。中身の決まり(タグの数・文字数)は指示文で伝え、解析([AnalysisParser])で整える。
 *
 * - クラウド向け([Limits] なし): 各社が受け付ける共通部分だけで書く(長さ・数の制約を書かない。object は
 *   additionalProperties: false と全項目 required)
 * - 端末内向け([Limits] あり): 繰り返しで出力が長くなり続けないよう、文字数とタグの数の上限も書く
 *   (llguidance は maxLength・maxItems に従い、上限で文字列・配列を閉じる)
 */
object AnalysisSchema {
    /** 要約・タグ・カテゴリのスキーマの名前(OpenAI の format.name) */
    const val ANALYSIS_NAME = "bookmark_analysis"

    /** 部分要約のメモのスキーマの名前 */
    const val NOTES_NAME = "bookmark_part_notes"

    /**
     * 端末内 AI で使う上限。長さは指示の文字数の数倍にし、普通の出力は切らずに、繰り返しに陥った出力だけを止める。
     * @param summaryMaxChars 要約の文字列の上限
     * @param maxTags タグの数の上限
     */
    data class Limits(val summaryMaxChars: Int, val maxTags: Int = AiPrompts.MAX_TAGS) {
        val tagMaxChars: Int get() = TAG_MAX_CHARS
        val categoryMaxChars: Int get() = CATEGORY_MAX_CHARS
    }

    /** [scope] で作る項目(要約だけ・タグとカテゴリだけ・全部)の出力のスキーマ(JSON の文字列)。 */
    fun analysis(scope: AnalysisScope, limits: Limits? = null): String {
        val properties = JSONObject()
        if (scope != AnalysisScope.TAGS) {
            properties.put(SUMMARY, stringSchema(limits?.summaryMaxChars))
        }
        if (scope != AnalysisScope.SUMMARY) {
            properties.put(
                TAGS,
                JSONObject()
                    .put("type", "array")
                    .put("items", stringSchema(limits?.tagMaxChars))
                    .apply { limits?.let { put("maxItems", it.maxTags) } }
            )
            properties.put(CATEGORY, stringSchema(limits?.categoryMaxChars))
        }
        return objectSchema(properties).toString()
    }

    /** 部分要約のメモ(`{"notes": "..."}`)のスキーマ。[maxChars] は端末内 AI で使う上限。 */
    fun notes(maxChars: Int? = null): String =
        objectSchema(JSONObject().put(NOTES, stringSchema(maxChars))).toString()

    /** [scope] で出力に入れる項目の名前(スキーマの required と同じ順)。 */
    fun fields(scope: AnalysisScope): List<String> = when (scope) {
        AnalysisScope.ALL -> listOf(SUMMARY, TAGS, CATEGORY)
        AnalysisScope.SUMMARY -> listOf(SUMMARY)
        AnalysisScope.TAGS -> listOf(TAGS, CATEGORY)
    }

    private fun stringSchema(maxLength: Int?): JSONObject =
        JSONObject().put("type", "string").apply { maxLength?.let { put("maxLength", it) } }

    private fun objectSchema(properties: JSONObject): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", JSONArray(properties.keys().asSequence().toList()))
        .put("additionalProperties", false)

    const val SUMMARY = "summary"
    const val TAGS = "tags"
    const val CATEGORY = "category"
    const val NOTES = "notes"

    /** タグ・カテゴリの文字数の上限(解析で使わない長さ。AnalysisParser の上限より長め) */
    private const val TAG_MAX_CHARS = 30
    private const val CATEGORY_MAX_CHARS = 20
}
