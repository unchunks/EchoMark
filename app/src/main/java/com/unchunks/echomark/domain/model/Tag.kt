package com.unchunks.echomark.domain.model

data class Tag(
    val id: Long,
    val name: String
)

/** タグと、そのタグが付いたブックマークの件数。 */
data class TagWithCount(
    val id: Long,
    val name: String,
    val bookmarkCount: Int
)
