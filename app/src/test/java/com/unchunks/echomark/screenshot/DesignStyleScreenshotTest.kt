package com.unchunks.echomark.screenshot

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.llm.ApiProvider
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.model.Tag
import com.unchunks.echomark.domain.repository.DesignStyle
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.repository.RediscoverSettings
import com.unchunks.echomark.testing.aiTasks
import com.unchunks.echomark.ui.bookmark.BookmarkListCallbacks
import com.unchunks.echomark.ui.bookmark.BookmarkListContent
import com.unchunks.echomark.ui.bookmark.BookmarkListUiState
import com.unchunks.echomark.ui.bookmark.DesignIntroBanner
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.ui.navigation.EchoMarkAppScaffold
import com.unchunks.echomark.ui.navigation.currentAdaptiveLayout
import com.unchunks.echomark.ui.settings.AiSummary
import com.unchunks.echomark.ui.settings.DesignStyleDialog
import com.unchunks.echomark.ui.settings.SettingsActions
import com.unchunks.echomark.ui.settings.SettingsContent
import com.unchunks.echomark.ui.settings.SettingsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 設定の「デザイン」で選べる見た目(エコー・ペーパー)。クラシックは各画面のスクリーンショットテストが撮っている。
 * 一覧(再発見の帯・カード・ナビゲーションバー)と設定画面、デザインを選ぶダイアログを撮る。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-xhdpi")
class DesignStyleScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val now = PreviewSamples.NOW
    private val day = 24 * 60 * 60 * 1000L

    private val listState = BookmarkListUiState(
        bookmarks = PreviewSamples.all,
        allTags = listOf(Tag(1, "Android"), Tag(2, "Compose"), Tag(3, "読書")),
        rediscover = listOf(
            Bookmark(
                id = 20, type = BookmarkType.URL, contentUri = "https://zenn.dev/articles/kotlin-flow",
                title = "Kotlin Flow の cold / hot を図で理解する", siteName = "Zenn",
                createdAt = now - 90 * day, lastAccessedAt = now - 60 * day
            ),
            Bookmark(
                id = 21, type = BookmarkType.TEXT, title = "来年やりたいことリスト",
                createdAt = now - 45 * day, lastAccessedAt = now - 45 * day
            )
        ),
        totalCount = PreviewSamples.all.size
    )

    private val settingsState = SettingsUiState(
        isLoaded = true,
        ai = AiSummary(
            tasks = aiTasks(LlmBackend.API, ApiProvider.CLAUDE),
            configuredProviders = setOf(ApiProvider.CLAUDE)
        ),
        rediscover = RediscoverSettings(enabled = true),
        versionLabel = "1.0 (1)"
    )

    @Composable
    private fun LibraryFrame(topNotice: (@Composable () -> Unit)? = null) {
        val layout = currentAdaptiveLayout()
        EchoMarkAppScaffold(
            showNavigation = true,
            selectedRoute = "bookmarks",
            onNavigate = {},
            navigation = layout.navigation
        ) { modifier ->
            BookmarkListContent(
                uiState = listState,
                snackbarHostState = SnackbarHostState(),
                callbacks = BookmarkListCallbacks(),
                nowMillis = now,
                modifier = modifier,
                topNotice = topNotice
            )
        }
    }

    @Composable
    private fun SettingsFrame(style: DesignStyle) {
        SettingsContent(
            uiState = settingsState.copy(designStyle = style),
            actions = SettingsActions(onOpenOnboarding = {})
        )
    }

    /** クラシックのまま一覧を開いた人に、一度だけ新しいデザインを案内する */
    @Test
    fun libraryDesignIntro() = screenshot.captureScreenLightDark("design_intro_library", heightDp = 860) {
        LibraryFrame(topNotice = { DesignIntroBanner(onChoose = {}, onDismiss = {}) })
    }

    @Test
    fun libraryEcho() = screenshot.captureScreenLightDark(
        "design_echo_library", heightDp = 860, designStyle = DesignStyle.ECHO
    ) { LibraryFrame() }

    @Test
    fun libraryPaper() = screenshot.captureScreenLightDark(
        "design_paper_library", heightDp = 860, designStyle = DesignStyle.PAPER
    ) { LibraryFrame() }

    @Test
    fun settingsEcho() = screenshot.captureScreenLightDark(
        "design_echo_settings", heightDp = 860, designStyle = DesignStyle.ECHO
    ) { SettingsFrame(DesignStyle.ECHO) }

    @Test
    fun settingsPaper() = screenshot.captureScreenLightDark(
        "design_paper_settings", heightDp = 860, designStyle = DesignStyle.PAPER
    ) { SettingsFrame(DesignStyle.PAPER) }

    @Test
    fun designDialog() = screenshot.captureDialogLightDark("settings_dialog_design") {
        DesignStyleDialog(selected = DesignStyle.ECHO, dynamicColor = false, onSelect = {}, onDismiss = {})
    }

    /** ダイナミックカラーがオンなら、配色は壁紙に合わせることを先に伝える */
    @Test
    fun designDialogWithDynamicColor() = screenshot.captureDialogLightDark("settings_dialog_design_dynamic_color") {
        DesignStyleDialog(selected = DesignStyle.CLASSIC, dynamicColor = true, onSelect = {}, onDismiss = {})
    }
}
