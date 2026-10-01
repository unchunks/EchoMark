package com.unchunks.echomark.domain.model

/** LLM によるブックマークの解析結果。 */
data class BookmarkAnalysis(
    val summary: String,
    val tags: List<String>,
    val category: String
)
