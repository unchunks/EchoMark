package com.unchunks.echomark.domain.model

/** 表記ゆれの候補(同じ意味のタグが別の名前で複数ある)の見つけ方。 */
object TagVariants {

    /**
     * [TagNames.variantKey] が同じタグをまとめて返す(2つ以上あるものだけ)。
     * 各グループの先頭が、残すタグの候補: ユーザーのタグ → 付いているブックマークが多い → 名前順。
     * グループは先頭のタグの名前順に並べる。
     */
    fun groups(tags: List<TagWithCount>): List<List<TagWithCount>> =
        tags.groupBy { TagNames.variantKey(it.name) }
            .filterKeys { it.isNotEmpty() }
            .values
            .filter { it.size >= 2 }
            .map { group ->
                group.sortedWith(
                    compareByDescending<TagWithCount> { it.isUserTag }
                        .thenByDescending { it.bookmarkCount }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first().name })
}
