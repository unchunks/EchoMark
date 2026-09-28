package com.unchunks.echomark.ui.navigation

/** 画面遷移で共有するルート定義。 */
object Routes {
    const val BOOKMARK_DETAIL = "bookmark/{bookmarkId}"
    fun bookmarkDetail(id: Long) = "bookmark/$id"

    /** 通知タップなど外部からの起動用ディープリンク。MainActivity の intent-filter と一致させること。 */
    const val BOOKMARK_DEEP_LINK = "echomark://bookmark/{bookmarkId}"
    fun bookmarkDeepLink(id: Long) = "echomark://bookmark/$id"
}
