package com.unchunks.echomark.screenshot

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.data.ai.model.LocalModelInfo
import com.unchunks.echomark.domain.model.TagWithCount
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.repository.RediscoverSettings
import com.unchunks.echomark.domain.repository.StorageUsage
import com.unchunks.echomark.domain.repository.ThemeMode
import com.unchunks.echomark.ui.bookmark.AddBookmarkSheet
import com.unchunks.echomark.ui.onboarding.AiSetupChoice
import com.unchunks.echomark.ui.onboarding.OnboardingActions
import com.unchunks.echomark.ui.onboarding.OnboardingContent
import com.unchunks.echomark.ui.onboarding.OnboardingUiState
import com.unchunks.echomark.ui.settings.AiSummary
import com.unchunks.echomark.ui.settings.SettingsActions
import com.unchunks.echomark.ui.settings.SettingsContent
import com.unchunks.echomark.ui.settings.SettingsUiState
import com.unchunks.echomark.ui.settings.ai.AiSettingsActions
import com.unchunks.echomark.ui.settings.ai.AiSettingsContent
import com.unchunks.echomark.ui.settings.ai.AiSettingsUiState
import com.unchunks.echomark.ui.settings.ai.ConnectionTestState
import com.unchunks.echomark.ui.share.ShareSaveStatus
import com.unchunks.echomark.ui.share.ShareSheetContent
import com.unchunks.echomark.ui.share.SharedContent
import com.unchunks.echomark.ui.tags.TagManagementContent
import com.unchunks.echomark.ui.tags.TagManagementUiState
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.DayOfWeek
import java.util.TimeZone

/**
 * タブレット・折りたたみ端末の展開時(幅 940dp)と、スマートフォンの横向き(800x360dp)の見た目。
 * 本文が [com.unchunks.echomark.ui.common.ReadableMaxWidth] までに収まって中央に寄り、
 * 低い画面でもスクロールで全部に届くことを確認する。ボトムシートは実際の ModalBottomSheet で撮る
 * (最大幅 640dp で中央に出る)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = LARGE_QUALIFIERS)
class LargeScreenScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private var originalTimeZone: TimeZone = TimeZone.getDefault()

    // AI 設定の取り込み日時の表示を実行環境のタイムゾーンに依存させない
    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
    }

    @After
    fun tearDown() = TimeZone.setDefault(originalTimeZone)

    private val settingsState = SettingsUiState(
        isLoaded = true,
        ai = AiSummary(
            backend = LlmBackend.API,
            apiProvider = ApiProvider.CLAUDE,
            apiModel = "claude-opus-5-5",
            apiKeyConfigured = true
        ),
        themeMode = ThemeMode.SYSTEM,
        dynamicColor = false,
        rediscover = RediscoverSettings(enabled = true, dayOfWeek = DayOfWeek.SUNDAY, hour = 20, minute = 0),
        storage = StorageUsage(databaseBytes = 1_300_000, embeddingBytes = 420_000, modelBytes = 556_000_000),
        versionLabel = "1.0 (1)"
    )

    private val shared = SharedContent(
        url = "https://www.example.com/articles/compose-performance?utm_source=share",
        text = "https://www.example.com/articles/compose-performance?utm_source=share",
        tentativeTitle = "https://www.example.com/articles/compose-performance?utm_source=share"
    )

    @Composable
    private fun SettingsScreen() = SettingsContent(
        uiState = settingsState,
        actions = SettingsActions(onOpenOnboarding = {}),
        notificationsBlocked = true
    )

    @Composable
    private fun AiSettingsScreen() = AiSettingsContent(
        uiState = AiSettingsUiState(
            backend = LlmBackend.API,
            localModel = LocalModelInfo(
                fileName = "local_llm.task",
                displayName = "gemma3-1b-it-int4.task",
                sizeBytes = 555_000_000,
                importedAt = 1_790_000_000_000L
            ),
            apiProvider = ApiProvider.CLAUDE,
            apiModels = ApiProvider.entries.associateWith { it.defaultModel },
            configuredProviders = setOf(ApiProvider.CLAUDE)
        ),
        connectionTest = ConnectionTestState.Success(ApiProvider.CLAUDE),
        actions = AiSettingsActions()
    )

    @Composable
    private fun TagsScreen() = TagManagementContent(
        uiState = TagManagementUiState(
            isLoading = false,
            tags = listOf(
                TagWithCount(1, "Android", 12),
                TagWithCount(2, "Compose", 8),
                TagWithCount(3, "パフォーマンス", 3, isUserTag = false),
                TagWithCount(4, "読書", 5),
                TagWithCount(5, "とても長いタグ名の例: 機械学習とデータ分析の基礎から応用まで", 1),
                TagWithCount(6, "未使用のタグ", 0)
            )
        ),
        snackbarHostState = SnackbarHostState(),
        onBack = {},
        onOpenTag = {},
        onRename = { _, _ -> },
        onDelete = {}
    )

    @Composable
    private fun Onboarding(page: Int, state: OnboardingUiState = OnboardingUiState()) = OnboardingContent(
        uiState = state,
        actions = OnboardingActions(),
        initialPage = page
    )

    // ---- 幅 940dp(タブレット・折りたたみの展開時) ----

    @Test
    fun settings() = screenshot.captureScreenLightDark("large_settings", LARGE_WIDTH_DP, LARGE_HEIGHT_DP) {
        SettingsScreen()
    }

    @Test
    fun aiSettings() = screenshot.captureScreenLightDark("large_ai_settings", LARGE_WIDTH_DP, LARGE_HEIGHT_DP) {
        AiSettingsScreen()
    }

    @Test
    fun tagManagement() = screenshot.captureScreenLightDark("large_tags", LARGE_WIDTH_DP, LARGE_HEIGHT_DP) {
        TagsScreen()
    }

    @Test
    fun onboardingWelcome() = screenshot.captureScreenLightDark("large_onboarding_1_welcome", LARGE_WIDTH_DP, LARGE_HEIGHT_DP) {
        Onboarding(0)
    }

    @Test
    fun onboardingAi() = screenshot.captureScreenLightDark("large_onboarding_3_ai", LARGE_WIDTH_DP, LARGE_HEIGHT_DP) {
        Onboarding(2, OnboardingUiState(aiChoice = AiSetupChoice.LOCAL))
    }

    @Test
    fun addBookmarkSheet() = screenshot.captureDialogLightDark("large_add_bookmark_sheet") {
        AddBookmarkSheet(onDismiss = {}, onSave = {})
    }

    @Test
    fun shareSheet() = screenshot.captureDialogLightDark("large_share_sheet") {
        ModalBottomSheet(
            onDismissRequest = {},
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            ShareSheetContent(
                shared = shared,
                status = ShareSaveStatus.Editing,
                title = "",
                memo = "",
                tags = "",
                onTitleChange = {},
                onMemoChange = {},
                onTagsChange = {},
                onSave = {},
                onCancel = {}
            )
        }
    }

    // ---- スマートフォンの横向き(幅 800dp x 高さ 360dp。縦にスクロールで全部に届くこと) ----

    @Test
    @Config(qualifiers = LANDSCAPE_QUALIFIERS)
    fun settingsLandscape() = screenshot.captureScreenLightDark("landscape_settings", LANDSCAPE_WIDTH_DP, LANDSCAPE_HEIGHT_DP) {
        SettingsScreen()
    }

    @Test
    @Config(qualifiers = LANDSCAPE_QUALIFIERS)
    fun aiSettingsLandscape() = screenshot.captureScreenLightDark("landscape_ai_settings", LANDSCAPE_WIDTH_DP, LANDSCAPE_HEIGHT_DP) {
        AiSettingsScreen()
    }

    @Test
    @Config(qualifiers = LANDSCAPE_QUALIFIERS)
    fun onboardingWelcomeLandscape() = screenshot.captureScreenLightDark("landscape_onboarding_1_welcome", LANDSCAPE_WIDTH_DP, LANDSCAPE_HEIGHT_DP) {
        Onboarding(0)
    }

    @Test
    @Config(qualifiers = LANDSCAPE_QUALIFIERS)
    fun onboardingAiLandscape() = screenshot.captureScreenLightDark("landscape_onboarding_3_ai", LANDSCAPE_WIDTH_DP, LANDSCAPE_HEIGHT_DP) {
        Onboarding(2, OnboardingUiState(aiChoice = AiSetupChoice.LOCAL))
    }

    @Test
    @Config(qualifiers = LANDSCAPE_QUALIFIERS)
    fun addBookmarkSheetLandscape() = screenshot.captureDialogLightDark("landscape_add_bookmark_sheet") {
        AddBookmarkSheet(onDismiss = {}, onSave = {})
    }
}

private const val LARGE_WIDTH_DP = 940
private const val LARGE_HEIGHT_DP = 1000
private const val LANDSCAPE_WIDTH_DP = 800
private const val LANDSCAPE_HEIGHT_DP = 360
private const val LARGE_QUALIFIERS = "w940dp-h1000dp-xhdpi"
private const val LANDSCAPE_QUALIFIERS = "w800dp-h360dp-xhdpi"
