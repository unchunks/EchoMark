package com.unchunks.echomark.screenshot

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ColorImage
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.github.takahirom.roborazzi.captureRoboImage
import com.unchunks.echomark.domain.model.TagWithCount
import com.unchunks.echomark.ui.bookmark.BookmarkListCallbacks
import com.unchunks.echomark.ui.bookmark.BookmarkListContent
import com.unchunks.echomark.ui.bookmark.BookmarkListUiState
import com.unchunks.echomark.ui.chat.ChatContent
import com.unchunks.echomark.ui.chat.ChatUiState
import com.unchunks.echomark.ui.chat.ConversationListContent
import com.unchunks.echomark.ui.chat.ConversationListUiState
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.ui.detail.BookmarkDetailCallbacks
import com.unchunks.echomark.ui.detail.BookmarkDetailContent
import com.unchunks.echomark.ui.detail.BookmarkDetailUiState
import com.unchunks.echomark.ui.navigation.EchoMarkAppScaffold
import com.unchunks.echomark.ui.onboarding.OnboardingActions
import com.unchunks.echomark.ui.onboarding.OnboardingContent
import com.unchunks.echomark.ui.onboarding.OnboardingUiState
import com.unchunks.echomark.ui.settings.SettingsActions
import com.unchunks.echomark.ui.settings.SettingsContent
import com.unchunks.echomark.ui.settings.SettingsUiState
import com.unchunks.echomark.ui.settings.ai.AiSettingsActions
import com.unchunks.echomark.ui.settings.ai.AiSettingsContent
import com.unchunks.echomark.ui.settings.ai.AiSettingsUiState
import com.unchunks.echomark.ui.settings.ai.ConnectionTestState
import com.unchunks.echomark.ui.tags.TagManagementContent
import com.unchunks.echomark.ui.tags.TagManagementUiState
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ステータスバー・ナビゲーションバーがある端末(エッジツーエッジ)での、アプリ全体の枠([EchoMarkAppScaffold])と各画面の余白。
 * 上の余白が二重・不足にならず、ステータスバーの帯が各画面のトップバーの色でつながること
 * (スクロールしてトップバーの色が変わる画面は、スクロール後を撮る)。
 *
 * Robolectric には実際のシステムバーが無いため、Compose のビューに仮のインセット
 * (ステータスバー 24dp・ナビゲーションバー 24dp)を渡して描く。ステータスバーの帯の位置が分かるよう、PNG は全体を撮る。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class WindowInsetsScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @OptIn(ExperimentalCoilApi::class)
    private fun capture(name: String, showBottomBar: Boolean, scrollVertically: Boolean = false, content: @Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides AsyncImagePreviewHandler { ColorImage(0xFF6F8FA6.toInt()) }
            ) {
                EchoMarkTheme(darkTheme = false, dynamicColor = false) {
                    EchoMarkAppScaffold(showNavigation = showBottomBar, selectedRoute = null, onNavigate = {}) { modifier ->
                        androidx.compose.foundation.layout.Box(modifier) { content() }
                    }
                }
            }
        }
        composeRule.runOnUiThread {
            val composeView = composeRule.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val density = composeRule.activity.resources.displayMetrics.density
            val bar = (BAR_DP * density).toInt()
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, bar, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bar))
                .build()
            ViewCompat.dispatchApplyWindowInsets(composeView, insets)
        }
        composeRule.waitForIdle()
        if (scrollVertically) {
            composeRule.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                .performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        composeRule.onRoot().captureRoboImage("insets_${name}.png")
    }

    @Test
    fun list() = capture("list", showBottomBar = true) {
        BookmarkListContent(
            uiState = BookmarkListUiState(bookmarks = PreviewSamples.all, totalCount = PreviewSamples.all.size),
            snackbarHostState = SnackbarHostState(),
            callbacks = BookmarkListCallbacks(),
            nowMillis = PreviewSamples.NOW
        )
    }

    @Test
    fun listSearch() = capture("list_search", showBottomBar = true) {
        BookmarkListContent(
            uiState = BookmarkListUiState(
                bookmarks = PreviewSamples.all, totalCount = PreviewSamples.all.size,
                isSearchActive = true, searchQuery = "Compose"
            ),
            snackbarHostState = SnackbarHostState(),
            callbacks = BookmarkListCallbacks(),
            nowMillis = PreviewSamples.NOW
        )
    }

    /** スクロールしてトップバーの色が変わった状態 */
    @Test
    fun detailScrolled() = capture("detail_scrolled", showBottomBar = false, scrollVertically = true) {
        BookmarkDetailContent(
            uiState = BookmarkDetailUiState(isLoading = false, bookmark = PreviewSamples.urlBookmark),
            snackbarHostState = SnackbarHostState(),
            callbacks = BookmarkDetailCallbacks(),
            nowMillis = PreviewSamples.NOW
        )
    }

    /** スクロールしてトップバーの色が変わった状態 */
    @Test
    fun tagsScrolled() = capture("tags_scrolled", showBottomBar = false, scrollVertically = true) {
        TagManagementContent(
            uiState = TagManagementUiState(
                isLoading = false,
                tags = (1L..30L).map { TagWithCount(it, "タグ$it", it.toInt()) }
            ),
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onOpenTag = {},
            onRename = { _, _ -> },
            onDelete = {}
        )
    }

    @Test
    fun conversationList() = capture("conversation_list", showBottomBar = true) {
        ConversationListContent(
            uiState = ConversationListUiState(isLoading = false),
            nowMillis = PreviewSamples.NOW,
            onOpenConversation = {},
            onNewConversation = {},
            onOpenAiSettings = {},
            onRename = { _, _ -> },
            onDelete = {}
        )
    }

    @Test
    fun chat() = capture("chat", showBottomBar = false) {
        ChatContent(
            uiState = ChatUiState(),
            input = "",
            onInputChange = {},
            onSend = {},
            onStop = {},
            onRetry = {},
            onBack = {},
            onOpenBookmark = {},
            onOpenAiSettings = {},
            onRename = {},
            onDelete = {},
            onCopy = {}
        )
    }

    @Test
    fun settings() = capture("settings", showBottomBar = true) {
        SettingsContent(uiState = SettingsUiState(isLoaded = true), actions = SettingsActions())
    }

    @Test
    fun aiSettings() = capture("ai_settings", showBottomBar = false) {
        AiSettingsContent(uiState = AiSettingsUiState(), connectionTest = ConnectionTestState.Idle, actions = AiSettingsActions())
    }

    @Test
    fun onboarding() = capture("onboarding", showBottomBar = false) {
        OnboardingContent(uiState = OnboardingUiState(), actions = OnboardingActions())
    }

    private companion object {
        const val BAR_DP = 24
    }
}
