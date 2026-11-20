package com.unchunks.echomark.data.ai.discover

import org.json.JSONObject

/**
 * 資料を選ぶ指示と、応答の解析。端末内 AI でも動くように、候補は番号で指定させ、番号の範囲と重複を解析側で検査する。
 */
object ResourceSelection {
    /** 1回の探索で選ぶ資料の上限 */
    const val MAX_PICKS = 5

    /** 候補の文字の上限(長い文字は予算を食うだけで、選ぶ手がかりにならない) */
    private const val MAX_CANDIDATE_CHARS = 80

    /** 調べたいことと候補から、役立つものの番号を JSON で選ばせる指示。 */
    fun prompt(topic: String, candidates: List<ResourceCandidate>, maxPicks: Int = MAX_PICKS): String = buildList {
        add("あなたは資料を選ぶアシスタントです。次の候補から、調べたいことに役立つものを最大${maxPicks}個選び、番号だけを JSON で出力してください。")
        add("説明文やコードブロックは出力しないでください。")
        add("")
        add("出力形式:")
        add("{\"picks\": [番号, 番号]}")
        add("")
        add("ルール:")
        add("- 候補に書かれている番号だけを使う。")
        add("- ログイン・広告・トップページだけのもの、調べたいことと関係ないものは選ばない。")
        add("- 役立つものが無ければ、picks は空の配列にする。")
        add("")
        add("調べたいこと: ${topic.trim()}")
        add("")
        add("候補:")
        candidates.forEachIndexed { index, candidate ->
            add("[${index + 1}] ${candidate.title.take(MAX_CANDIDATE_CHARS)}")
        }
    }.joinToString("\n")

    /**
     * 応答から選ばれた候補の位置(0 始まり)を取り出す。候補の範囲外・重複は捨て、[maxPicks] 個までにする。
     * JSON の形が崩れていれば、選ばれた候補は無いものとして空を返す(保存しない)。
     */
    fun parsePicks(response: String, candidateCount: Int, maxPicks: Int = MAX_PICKS): List<Int> {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val array = try {
            JSONObject(response.substring(start, end + 1)).optJSONArray("picks")
        } catch (e: org.json.JSONException) {
            return emptyList()
        } ?: return emptyList()

        val picks = LinkedHashSet<Int>()
        for (i in 0 until array.length()) {
            if (picks.size >= maxPicks) break
            // 番号は 1 始まり。範囲外・数値でないものは捨てる
            val number = array.optInt(i, -1)
            if (number in 1..candidateCount) picks += number - 1
        }
        return picks.toList()
    }
}
