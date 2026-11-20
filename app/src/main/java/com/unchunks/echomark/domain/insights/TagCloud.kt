package com.unchunks.echomark.domain.insights

import com.unchunks.echomark.domain.model.TagWithCount
import kotlin.math.ln

/**
 * タグクラウドの1語。
 * @param weight 0(いちばん少ない)〜1(いちばん多い)。件数の対数で決めるので、1つだけ極端に多くても他が潰れない
 */
data class TagCloudEntry(val tag: TagWithCount, val weight: Float)

/** タグクラウドに出す語と、載せきれなかった数。 */
data class TagCloudResult(val entries: List<TagCloudEntry>, val hiddenCount: Int)

/** タグの件数から、タグクラウドの語と大きさを決める純粋な処理。 */
object TagCloud {
    /** 一度に出す語の上限(多すぎると読めない。残りは「ほか N 個」で知らせる) */
    const val MAX_TAGS = 60

    /**
     * 件数 1 以上のタグから、多い順に [maxTags] 個を選び、名前順に並べて大きさ(0〜1)を付ける。
     * 件数がすべて同じなら中くらい(0.5)にそろえる。
     */
    fun build(tags: List<TagWithCount>, maxTags: Int = MAX_TAGS): TagCloudResult {
        val used = tags.filter { it.bookmarkCount > 0 }
        if (used.isEmpty()) return TagCloudResult(emptyList(), 0)
        val picked = used
            .sortedWith(compareByDescending<TagWithCount> { it.bookmarkCount }.thenBy { it.name.lowercase() })
            .take(maxTags)
        val low = ln(picked.minOf { it.bookmarkCount }.toDouble())
        val high = ln(picked.maxOf { it.bookmarkCount }.toDouble())
        val entries = picked
            .map { tag ->
                val weight = if (high - low < 1e-9) 0.5f else ((ln(tag.bookmarkCount.toDouble()) - low) / (high - low)).toFloat()
                TagCloudEntry(tag, weight)
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.tag.name })
        return TagCloudResult(entries, hiddenCount = used.size - picked.size)
    }
}
