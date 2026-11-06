package com.unchunks.echomark.ui.navigation

/** 画面遷移で共有するルート定義。 */
object Routes {
    /** 設定タブ内の AI 設定サブ画面。"settings/..." なのでボトムバーは設定タブが選択状態になる。 */
    const val AI_SETTINGS = "settings/ai"

    /** カテゴリの管理(設定の「AI」の欄から開くサブ画面)。"settings/..." なのでボトムバーは設定タブが選択状態になる */
    const val CATEGORIES = "settings/categories"

    /** タグの管理(ブックマーク一覧のメニューから開くサブ画面) */
    const val TAGS = "tags"

    /** 資料を探す(ブックマーク一覧のメニューから開くサブ画面) */
    const val DISCOVER = "discover"

    /** 重複の整理と関連のまとまり(ブックマーク一覧のメニューから開くサブ画面) */
    const val DUPLICATES = "duplicates"

    /** 読む・ふり返る(今週のふり返り・あとで読む・復習。ブックマーク一覧のメニュー・週1回の通知から開くサブ画面) */
    const val REVIEW = "review"

    /** 復習(ふり返りの画面から開く) */
    const val REVIEW_SESSION = "review/session"

    /** 通知から今週のふり返りを開くディープリンク。MainActivity の intent-filter と一致させること */
    const val REVIEW_DEEP_LINK = "echomark://review"

    /** ナレッジグラフ(ブックマークのつながり。ライブラリの分析の画面から開くサブ画面) */
    const val GRAPH = "insights/graph"

    /** 場所のあるブックマークの分布図と一覧(ライブラリの分析の画面から開くサブ画面) */
    const val PLACES = "insights/places"

    /** ライブラリの分析(タグクラウド・統計・タイムライン・関心の変化。ブックマーク一覧のメニューから開くサブ画面) */
    const val INSIGHTS = "insights"

    /** コレクションの一覧(ブックマーク一覧のメニューから開くサブ画面) */
    const val COLLECTIONS = "collections"

    /** リンク切れの一覧(ブックマーク一覧のメニューから開くサブ画面) */
    const val BROKEN_LINKS = "broken-links"

    /** コレクションの中身(コレクションの一覧から開く) */
    const val COLLECTION_DETAIL = "collections/{collectionId}"
    fun collectionDetail(id: Long) = "collections/$id"

    /** ホーム画面のショートカットなど外部からコレクションを開くディープリンク。MainActivity の intent-filter と一致させること。 */
    const val COLLECTION_DEEP_LINK = "echomark://collection/{collectionId}"
    fun collectionDeepLink(id: Long) = "echomark://collection/$id"

    /** 一覧ページのリンク・端末内のファイルから資料を探す(資料を探す画面から開く) */
    const val DISCOVER_FROM_PAGE_OR_FILES = "discover/page-or-files"

    /** 処理の状況(設定の「処理」の欄・まとめての処理の通知から開く)。"settings/..." なので設定タブが選択状態になる */
    const val PROCESSING_QUEUE = "settings/processing-queue"

    /** まとめての処理の通知から処理の状況を開くディープリンク。MainActivity の intent-filter と一致させること */
    const val PROCESSING_QUEUE_DEEP_LINK = "echomark://processing-queue"

    /** 設定の「はじめにの案内をもう一度見る」から開くオンボーディング(初回起動時は NavHost の外で表示する)。 */
    const val ONBOARDING = "settings/onboarding"

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
