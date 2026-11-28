package com.unchunks.echomark.screenshot.store

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.window.core.layout.WindowSizeClass
import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.graph.GraphOptions
import com.unchunks.echomark.domain.graph.KnowledgeGraphBuilder
import com.unchunks.echomark.domain.graph.forceLayout
import com.unchunks.echomark.domain.model.Conversation
import com.unchunks.echomark.domain.model.ConversationPreview
import com.unchunks.echomark.domain.review.ReadingList
import com.unchunks.echomark.domain.review.WeeklyReviewCalculator
import com.unchunks.echomark.ui.bookmark.BookmarkListCallbacks
import com.unchunks.echomark.ui.bookmark.BookmarkListContent
import com.unchunks.echomark.ui.bookmark.BookmarkListUiState
import com.unchunks.echomark.ui.chat.AnswerReview
import com.unchunks.echomark.ui.chat.ChatContent
import com.unchunks.echomark.ui.chat.ChatUiState
import com.unchunks.echomark.ui.chat.CitedSource
import com.unchunks.echomark.ui.chat.ConversationListContent
import com.unchunks.echomark.ui.chat.ConversationListUiState
import com.unchunks.echomark.ui.detail.BookmarkDetailCallbacks
import com.unchunks.echomark.ui.detail.BookmarkDetailContent
import com.unchunks.echomark.ui.detail.BookmarkDetailUiState
import com.unchunks.echomark.ui.explore.ExploreActions
import com.unchunks.echomark.ui.explore.ExploreScreen
import com.unchunks.echomark.ui.graph.GraphContent
import com.unchunks.echomark.ui.graph.GraphMode
import com.unchunks.echomark.ui.graph.GraphUiState
import com.unchunks.echomark.ui.navigation.AdaptiveLayout
import com.unchunks.echomark.ui.navigation.EchoMarkAppScaffold
import com.unchunks.echomark.ui.navigation.ListDetailPanes
import com.unchunks.echomark.ui.navigation.NavigationLayout
import com.unchunks.echomark.ui.navigation.adaptiveLayoutOf
import com.unchunks.echomark.ui.onboarding.AiSetupChoice
import com.unchunks.echomark.ui.onboarding.OnboardingActions
import com.unchunks.echomark.ui.onboarding.OnboardingContent
import com.unchunks.echomark.ui.onboarding.OnboardingUiState
import com.unchunks.echomark.ui.review.ReviewHubActions
import com.unchunks.echomark.ui.review.ReviewHubContent
import com.unchunks.echomark.ui.review.ReviewHubTab
import com.unchunks.echomark.ui.review.ReviewHubUiState
import com.unchunks.echomark.ui.share.ShareSaveStatus
import com.unchunks.echomark.ui.share.ShareSheetContent
import com.unchunks.echomark.ui.share.SharedContent
import java.time.ZoneId

/**
 * ストアの画像と紹介動画に写すアプリの画面。本物の画面(XxxContent)に [StoreSamples] のデータを渡して描く。
 * スクリーンショットと動画で同じ画面を使い、見た目がずれないようにしている。
 */
internal object StoreScreens {
    private val now = StoreSamples.NOW
    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")

    // ---- スマートフォン ----

    /** ボトムバーつきのトップレベル画面 */
    @Composable
    fun TopLevel(route: String, content: @Composable (Modifier) -> Unit) {
        EchoMarkAppScaffold(
            showNavigation = true,
            selectedRoute = route,
            onNavigate = {},
            navigation = NavigationLayout.BAR,
            content = content
        )
    }

    val homeState = BookmarkListUiState(
        bookmarks = StoreSamples.library,
        allTags = StoreSamples.tags,
        rediscover = StoreSamples.rediscover,
        totalCount = 128
    )

    @Composable
    fun Home(state: BookmarkListUiState = homeState, modifier: Modifier = Modifier, selectedId: Long? = null) {
        BookmarkListContent(
            uiState = state,
            snackbarHostState = SnackbarHostState(),
            callbacks = BookmarkListCallbacks(),
            modifier = modifier,
            nowMillis = now,
            selectedBookmarkId = selectedId
        )
    }

    /** [query] まで入力した検索。[showResults] が false なら結果はまだ出さない(動画で入力の途中を見せる) */
    fun searchState(query: String = StoreSamples.SEARCH_QUERY, showResults: Boolean = true) = BookmarkListUiState(
        bookmarks = if (showResults) StoreSamples.searchResults else emptyList(),
        allTags = StoreSamples.tags,
        isSearchActive = true,
        searchQuery = query,
        queryRevision = query.length,
        searchTerms = if (showResults) listOf(query) else emptyList(),
        searchMatches = if (showResults) StoreSamples.searchMatches else emptyMap(),
        semanticMatchIds = if (showResults) StoreSamples.searchResults.map { it.id }.toSet() else emptySet(),
        isSearching = !showResults && query.isNotEmpty(),
        totalCount = 128
    )

    @Composable
    fun Detail(bookmark: Bookmark = StoreSamples.kyoto, showBackButton: Boolean = true) {
        BookmarkDetailContent(
            uiState = BookmarkDetailUiState(
                isLoading = false,
                bookmark = bookmark,
                related = if (bookmark.id == StoreSamples.kyoto.id) {
                    listOf(StoreSamples.kyotoMemo, StoreSamples.garden)
                } else {
                    listOf(StoreSamples.stretch, StoreSamples.habitBook)
                }
            ),
            snackbarHostState = SnackbarHostState(),
            callbacks = BookmarkDetailCallbacks(),
            nowMillis = now,
            showBackButton = showBackButton
        )
    }

    val sharedKyoto = SharedContent(
        url = StoreSamples.kyoto.contentUri,
        text = StoreSamples.kyoto.contentUri!!,
        tentativeTitle = StoreSamples.kyoto.title
    )

    /** ブラウザで記事を開き、共有から EchoMark を選んだところ */
    @Composable
    fun Share(status: ShareSaveStatus = ShareSaveStatus.Editing, tags: String = "旅行") {
        Box(Modifier.fillMaxSize()) {
            FakeBrowserPage(host = "travel.example.jp", title = StoreSamples.kyoto.title)
            BottomSheetMock {
                ShareSheetContent(
                    shared = sharedKyoto,
                    status = status,
                    title = "",
                    memo = "",
                    tags = tags,
                    suggestedTags = listOf("京都", "紅葉", "旅行", "あとで読む"),
                    onTitleChange = {},
                    onMemoChange = {},
                    onTagsChange = {},
                    onSave = {},
                    onCancel = {}
                )
            }
        }
    }

    fun chatState(
        answer: String? = StoreSamples.CHAT_ANSWER,
        streaming: String? = null
    ): ChatUiState {
        val messages = buildList {
            add(StoreSamples.chatQuestion)
            if (answer != null && streaming == null) add(StoreSamples.chatAnswer.copy(content = answer))
        }
        return ChatUiState(
            title = "京都旅行の前に、保存した記事と…",
            hasConversation = true,
            messages = messages,
            referencedBookmarks = StoreSamples.chatReferenced,
            isSending = streaming != null,
            streamingText = streaming,
            pendingReferenceCount = if (streaming != null) 2 else null,
            answerReviews = if (answer != null && streaming == null) {
                mapOf(
                    StoreSamples.chatAnswer.id to AnswerReview(
                        sources = listOf(
                            CitedSource(1, StoreSamples.kyoto.id, "人気の寺院は昼前から混み合うため、朝 8 時前の拝観がおすすめ。"),
                            CitedSource(2, StoreSamples.kyotoMemo.id, null)
                        )
                    )
                )
            } else {
                emptyMap()
            }
        )
    }

    @Composable
    fun Chat(state: ChatUiState = chatState(), input: String = "", showBackButton: Boolean = true) {
        ChatContent(
            uiState = state,
            input = input,
            onInputChange = {},
            onSend = {},
            onStop = {},
            onRetry = {},
            onBack = {},
            onOpenBookmark = {},
            onOpenAiSettings = {},
            onRename = {},
            onDelete = {},
            onCopy = {},
            showBackButton = showBackButton
        )
    }

    private val reviewBookmarks: List<Bookmark> = listOf(
        StoreSamples.sleep.copy(readAt = now - 1 * DAY),
        StoreSamples.kyoto.copy(readAt = now - 1 * DAY),
        StoreSamples.mealPrep.copy(readingListAt = now - 2 * DAY),
        StoreSamples.focus.copy(readAt = now - 3 * DAY),
        StoreSamples.kyotoMemo,
        StoreSamples.ai.copy(readingListAt = now - 4 * DAY),
        StoreSamples.movingPdf,
        StoreSamples.stretch,
        StoreSamples.habitBook,
        StoreSamples.garden
    )

    @Composable
    fun Review() {
        ReviewHubContent(
            uiState = ReviewHubUiState(
                isLoading = false,
                weekly = WeeklyReviewCalculator.compute(reviewBookmarks, now, dueReviews = 2),
                readingList = ReadingList.of(reviewBookmarks),
                dueCount = 2
            ),
            snackbarHostState = SnackbarHostState(),
            actions = ReviewHubActions(),
            initialTab = ReviewHubTab.WEEKLY,
            nowMillis = now,
            zone = zone
        )
    }

    private val graph = KnowledgeGraphBuilder.build(
        StoreSamples.graphBookmarks,
        groups = listOf(StoreSamples.graphBookmarks.map { it.id }.filter { it % 4 == 0L }.take(6)),
        options = GraphOptions(maxNodes = 40)
    )

    @Composable
    fun Graph() {
        GraphContent(
            uiState = GraphUiState(
                isLoading = false,
                graph = graph,
                layout = graph.forceLayout(),
                filterTags = listOf("旅行", "健康", "料理", "仕事術", "暮らし"),
                bookmarkCount = StoreSamples.graphBookmarks.size
            ),
            onBack = {}, onOpenBookmark = {}, onRefresh = {}, onSelect = {}, onMaxNodes = {}, onTagFilter = {},
            initialMode = GraphMode.GRAPH
        )
    }

    @Composable
    fun AiChoice() {
        OnboardingContent(
            uiState = OnboardingUiState(aiChoice = AiSetupChoice.LOCAL),
            actions = OnboardingActions(),
            initialPage = 2
        )
    }

    // ---- タブレット(横向き) ----

    fun layoutOf(widthDp: Int, heightDp: Int): AdaptiveLayout =
        adaptiveLayoutOf(WindowAdaptiveInfo(WindowSizeClass(widthDp.toFloat(), heightDp.toFloat()), Posture()))

    @Composable
    fun TabletFrame(layout: AdaptiveLayout, route: String, content: @Composable (Modifier) -> Unit) {
        EchoMarkAppScaffold(
            showNavigation = true,
            selectedRoute = route,
            onNavigate = {},
            navigation = layout.navigation,
            content = content
        )
    }

    /** 一覧と詳細の2画面 */
    @Composable
    fun TabletLibrary(layout: AdaptiveLayout, list: BookmarkListUiState, selected: Bookmark) {
        TabletFrame(layout, "bookmarks") { modifier ->
            ListDetailPanes(
                directive = layout.paneDirective,
                modifier = modifier,
                list = { Home(list, selectedId = selected.id) },
                detail = { Detail(selected, showBackButton = false) }
            )
        }
    }

    private val conversations = listOf(
        preview(1, "京都旅行の準備", "2 日目の昼は、メモにあった南禅寺の近くの湯豆腐がルートの途中です。", 2),
        preview(2, "眠りの質を上げるには", "まずは寝る 1 時間前にスマホを置くことから始めましょう。", 60 * 26),
        preview(3, "作り置きの献立を考える", "日曜に 90 分あれば、5 品を作り置きできます。", 60 * 24 * 3),
        preview(4, "集中が続く働き方", "25 分の作業と 5 分の休憩をくり返すのが基本です。", 60 * 24 * 6)
    )

    private fun preview(id: Long, title: String, last: String, minutesAgo: Long) = ConversationPreview(
        conversation = Conversation(id = id, title = title, createdAt = now - minutesAgo * MINUTE - 10 * MINUTE, updatedAt = now - minutesAgo * MINUTE),
        lastMessage = last,
        lastMessageRole = ChatRole.ASSISTANT
    )

    @Composable
    fun TabletChat(layout: AdaptiveLayout) {
        TabletFrame(layout, "chat") { modifier ->
            ListDetailPanes(
                directive = layout.paneDirective,
                modifier = modifier,
                list = {
                    ConversationListContent(
                        uiState = ConversationListUiState(isLoading = false, conversations = conversations),
                        nowMillis = now,
                        onOpenConversation = {},
                        onNewConversation = {},
                        onOpenAiSettings = {},
                        onRename = { _, _ -> },
                        onDelete = {},
                        selectedConversationId = 1
                    )
                },
                detail = { Chat(chatState().copy(title = "京都旅行の準備"), showBackButton = false) }
            )
        }
    }

    @Composable
    fun TabletExplore(layout: AdaptiveLayout) {
        TabletFrame(layout, "explore") { modifier -> Box(modifier) { ExploreScreen(actions = ExploreActions()) } }
    }

    @Composable
    fun TabletGraph(layout: AdaptiveLayout) {
        TabletFrame(layout, "explore") { modifier -> Box(modifier) { Graph() } }
    }

    private const val MINUTE = 60_000L
    private const val DAY = 24 * 60 * MINUTE

    // ---- 設定(store-assets.json)の phone[].screen・tablet[].screen で選べる画面 ----

    /** 詳細は、リンク先の画像の分だけスクロールして AI の要約を枠に収める */
    private val detailScroll = StoreScroll(text = "ブラウザで開く", phoneDp = 205f, tabletDp = 395f)

    /** スマートフォンの画面。名前 → 画面 */
    val phoneScreens: Map<String, StoreScreen> = mapOf(
        "home" to StoreScreen { TopLevel("bookmarks") { Home(modifier = it) } },
        "share" to StoreScreen { Share() },
        "detail" to StoreScreen(detailScroll) { Detail() },
        "search" to StoreScreen { TopLevel("bookmarks") { Home(searchState(), modifier = it) } },
        "chat" to StoreScreen { Chat() },
        "review" to StoreScreen { Review() },
        "graph" to StoreScreen { Graph() },
        "ai" to StoreScreen { AiChoice() },
        "explore" to StoreScreen { TopLevel("explore") { ExploreScreen(actions = ExploreActions(), modifier = it) } }
    )

    /** タブレット(横向き)の画面。名前 → 画面 */
    val tabletScreens: Map<String, StoreTabletScreen> = mapOf(
        "library" to StoreTabletScreen(detailScroll) { TabletLibrary(it, homeState, StoreSamples.kyoto) },
        "search" to StoreTabletScreen(detailScroll) { TabletLibrary(it, searchState(), StoreSamples.sleep) },
        "chat" to StoreTabletScreen { TabletChat(it) },
        "graph" to StoreTabletScreen { TabletGraph(it) },
        "explore" to StoreTabletScreen { TabletExplore(it) }
    )
}

/** 撮る前に、[text] を含むスクロールできる領域を下へスクロールする量 */
internal class StoreScroll(val text: String, val phoneDp: Float, val tabletDp: Float)

internal class StoreScreen(val scroll: StoreScroll? = null, val content: @Composable () -> Unit)

internal class StoreTabletScreen(val scroll: StoreScroll? = null, val content: @Composable (AdaptiveLayout) -> Unit)
