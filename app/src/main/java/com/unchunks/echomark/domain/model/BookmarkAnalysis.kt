package com.unchunks.echomark.domain.model

/** LLM によるブックマークの解析結果。 */
data class BookmarkAnalysis(
    val summary: String,
    val tags: List<String>,
    val category: String
)

/**
 * 1回の推論で作る項目。タグ付けと要約に別々の AI を使うときは、[SUMMARY] と [TAGS] に分けて2回推論する。
 * 作らない項目の値([BookmarkAnalysis] の既定の代替)は使わないこと。
 */
enum class AnalysisScope {
    /** 要約・タグ・カテゴリをまとめて作る */
    ALL,

    /** 要約だけ */
    SUMMARY,

    /** タグとカテゴリだけ */
    TAGS
}
