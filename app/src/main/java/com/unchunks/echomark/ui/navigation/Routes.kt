package com.unchunks.echomark.ui.navigation

/** 画面遷移で共有するルート定義。 */
object Routes {
    const val BOOKMARK_DETAIL = "bookmark/{bookmarkId}"
    fun bookmarkDetail(id: Long) = "bookmark/$id"
}
