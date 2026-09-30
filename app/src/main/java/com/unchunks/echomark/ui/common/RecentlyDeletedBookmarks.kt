package com.unchunks.echomark.ui.common

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 詳細画面で削除したブックマークを、戻った先の一覧画面へ渡すための受け渡し口。
 * 一覧はこれを受け取って「削除しました/元に戻す」の Snackbar を出す(復元には削除前の Bookmark 全体が要る)。
 * 古い通知で後から Snackbar が出ないよう、[MAX_AGE_MILLIS] を過ぎたものは捨てる。
 */
@Singleton
class RecentlyDeletedBookmarks @Inject constructor() {

    data class Entry(val bookmark: Bookmark, val deletedAt: Long)

    private val _pending = MutableStateFlow<Entry?>(null)
    val pending: StateFlow<Entry?> = _pending.asStateFlow()

    fun notifyDeleted(bookmark: Bookmark, now: Long = System.currentTimeMillis()) {
        _pending.value = Entry(bookmark, now)
    }

    /** 受け取り待ちの削除を取り出す(取り出したら空にする)。古すぎるものは null。 */
    fun consume(now: Long = System.currentTimeMillis()): Bookmark? =
        _pending.getAndUpdate { null }
            ?.takeIf { now - it.deletedAt <= MAX_AGE_MILLIS }
            ?.bookmark

    companion object {
        const val MAX_AGE_MILLIS = 10_000L
    }
}
