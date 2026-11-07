package com.unchunks.echomark.domain.model

/**
 * 検索インデックス(埋め込み)の更新状況。
 * [embedded] は現在の埋め込みモデルの版で保存済みの件数、[total] はブックマークの総数。
 */
data class EmbeddingProgress(val embedded: Int, val total: Int) {
    /** 全ブックマークが現在の版でそろっているか(ブックマークが無いときも true) */
    val isComplete: Boolean get() = embedded >= total
}
