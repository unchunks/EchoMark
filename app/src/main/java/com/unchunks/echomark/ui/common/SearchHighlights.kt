package com.unchunks.echomark.ui.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 検索結果から開いたブックマークの本文で、検索語を強調するための受け渡し口(#57)。
 * 一覧が今の検索語を [setTerms] で知らせ、結果を開くときに [open] でどのブックマークかを知らせる。
 * 詳細画面は自分のブックマークの分だけを [termsFor] で受け取る(関連やチャットの出典から開いたものは強調しない)。
 * 検索を閉じると検索語が空になり、強調も消える。
 */
@Singleton
class SearchHighlights @Inject constructor() {

    private data class Active(val terms: List<String> = emptyList(), val bookmarkId: Long? = null)

    private val state = MutableStateFlow(Active())

    /** 一覧の今の検索語(空白で分けたもの)。検索していなければ空 */
    fun setTerms(terms: List<String>) {
        state.update { Active(terms, bookmarkId = if (terms.isEmpty()) null else it.bookmarkId) }
    }

    /** 検索結果から [bookmarkId] を開いた。検索していなければ何もしない */
    fun open(bookmarkId: Long) {
        state.update { if (it.terms.isEmpty()) it else it.copy(bookmarkId = bookmarkId) }
    }

    /** [bookmarkId] の詳細画面で強調する検索語。強調しないなら空 */
    fun termsFor(bookmarkId: Long): Flow<List<String>> =
        state.map { if (it.bookmarkId == bookmarkId) it.terms else emptyList() }.distinctUntilChanged()
}
