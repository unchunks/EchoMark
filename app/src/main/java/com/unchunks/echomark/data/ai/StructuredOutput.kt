package com.unchunks.echomark.data.ai

import com.unchunks.echomark.domain.model.AnalysisScope
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * 要約・タグ・カテゴリの出力が [AnalysisSchema] の形になっているかを確かめ、壊れていれば作り直させる。
 * スキーマで出力を縛っても(クラウドの構造化出力・端末内の制約付きの生成)、対応しないモデル・空の要約などで崩れることがあるため、
 * 形を確かめて、決めた回数まで直す指示を付けて作り直させる。それでも直らなければ最後の出力を返し、
 * 呼び出し側の緩い解析([com.unchunks.echomark.data.ai.local.AnalysisParser])が使える部分を拾う。
 */
object StructuredOutput {

    /**
     * [response] が [scope] の出力の形として正しくなければ、その理由(作り直しの指示に入れる日本語)。正しければ null。
     * 確かめること: JSON のオブジェクトが1つあり、summary は空でない文字列、tags は文字列の配列、category は空でない文字列。
     * 前後の余計な文字(コードブロックの記号など)は、解析で除けるため問題にしない。
     */
    fun problemOf(response: String, scope: AnalysisScope): String? {
        val json = jsonObjectIn(response) ?: return "JSON のオブジェクトになっていません"
        for (field in AnalysisSchema.fields(scope)) {
            if (!json.has(field)) return "$field がありません"
            val value = json.get(field)
            when (field) {
                AnalysisSchema.TAGS -> {
                    if (value !is JSONArray) return "tags が配列になっていません"
                    if ((0 until value.length()).any { value.get(it) !is String }) return "tags に文字列でない値があります"
                }
                else -> {
                    if (value !is String) return "$field が文字列になっていません"
                    if (value.isBlank()) return "$field が空です"
                }
            }
        }
        return null
    }

    /**
     * 出力を作り、[validate] が理由を返したら(形が正しくなければ)、直す指示を付けて作り直させる。作り直すのは [maxRepairs] 回まで。
     * @param generate 出力を作る。1回目は problem と previous が null、作り直しではそれまでの出力の誤りの理由と、その出力
     * @return 形の正しい出力。作り直しても正しくならなければ最後の出力
     */
    suspend fun generateWithRepair(
        maxRepairs: Int,
        label: String,
        validate: (String) -> String?,
        generate: suspend (problem: String?, previous: String?) -> String
    ): String {
        var problem: String? = null
        var previous: String? = null
        for (attempt in 0..maxRepairs) {
            val response = generate(problem, previous)
            problem = validate(response) ?: return response
            previous = response
            Timber.w("%s の出力の形が正しくない(%d 回目): %s", label, attempt + 1, problem)
        }
        return previous!!
    }

    /** 最初の `{` から最後の `}` までを JSON のオブジェクトとして読む。読めなければ null。 */
    private fun jsonObjectIn(response: String): JSONObject? {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(response.substring(start, end + 1))
        } catch (e: Exception) {
            null
        }
    }
}
