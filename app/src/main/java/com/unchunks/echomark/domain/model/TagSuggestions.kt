package com.unchunks.echomark.domain.model

/** 保存するときに出すタグの提案の選び方(提案の元は、保存する内容に出てくる既存のタグ・似たブックマークのタグ・よく使うタグ)。 */
object TagSuggestions {

    /** 保存する内容に名前(や別名)が出てくる既存のタグの点数。似たブックマークの上位より高い */
    private const val MENTIONED_SCORE = 3.0

    /** 似たブックマークの点数(いちばん似ているものが 2.0、順位が下がるほど下がる) */
    private const val SIMILAR_SCORE = 2.0

    /**
     * 提案するタグを、点数の高い順に最大 [limit] 個選ぶ。
     * @param mentioned 保存する内容に名前や別名が出てくる既存のタグ(多くの根拠があるので最優先)
     * @param similar 似ているブックマークのタグ(似ている順に、ブックマークごと)
     * @param popular よく使うタグ(優先する順)。上の 2 つで [limit] に足りないときだけ補う
     * @param exclude 既に入力されている・付けるタグ(キーで比べるので、表記ゆれは同じタグ扱い)
     */
    fun rank(
        mentioned: List<String>,
        similar: List<List<String>>,
        popular: List<String>,
        exclude: Collection<String> = emptyList(),
        limit: Int = 6
    ): List<String> {
        val excluded = exclude.mapTo(HashSet()) { TagNames.key(it) }
        val names = LinkedHashMap<String, String>()
        val scores = HashMap<String, Double>()
        fun add(name: String, score: Double) {
            val key = TagNames.key(name)
            if (key.isEmpty() || key in excluded) return
            names.putIfAbsent(key, name)
            scores[key] = (scores[key] ?: 0.0) + score
        }
        mentioned.forEach { add(it, MENTIONED_SCORE) }
        similar.forEachIndexed { rank, tags -> tags.forEach { add(it, SIMILAR_SCORE / (rank + 1)) } }

        val ranked = names.keys.sortedByDescending { scores.getValue(it) }.map { names.getValue(it) }.take(limit)
        if (ranked.size >= limit) return ranked

        val result = ranked.toMutableList()
        val taken = result.mapTo(HashSet()) { TagNames.key(it) }
        for (name in popular) {
            if (result.size >= limit) break
            val key = TagNames.key(name)
            if (key.isNotEmpty() && key !in excluded && taken.add(key)) result += name
        }
        return result
    }
}
