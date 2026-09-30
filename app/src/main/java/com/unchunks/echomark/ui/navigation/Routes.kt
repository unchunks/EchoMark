package com.unchunks.echomark.ui.navigation

/** 画面遷移で共有するルート定義。 */
object Routes {
    /** 設定タブ内の AI 設定サブ画面。"settings/..." なのでボトムバーは設定タブが選択状態になる。 */
    const val AI_SETTINGS = "settings/ai"

    const val BOOKMARK_DETAIL = "bookmark/{bookmarkId}"
    fun bookmarkDetail(id: Long) = "bookmark/$id"

    /** 通知タップなど外部からの起動用ディープリンク。MainActivity の intent-filter と一致させること。 */
    const val BOOKMARK_DEEP_LINK = "echomark://bookmark/{bookmarkId}"
    fun bookmarkDeepLink(id: Long) = "echomark://bookmark/$id"

    /** 特定のブックマークについて質問する新規チャット(詳細画面の「AIに質問」から開く)。 */
    const val ARG_ABOUT_BOOKMARK_ID = "aboutBookmarkId"
    const val CHAT_NEW_ABOUT_BOOKMARK = "chat/new/about/{$ARG_ABOUT_BOOKMARK_ID}"
    fun chatAboutBookmark(bookmarkId: Long) = "chat/new/about/$bookmarkId"
}
