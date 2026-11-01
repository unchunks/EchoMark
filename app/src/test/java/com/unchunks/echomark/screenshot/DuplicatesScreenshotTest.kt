package com.unchunks.echomark.screenshot

import androidx.compose.material3.SnackbarHostState
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.bookmark.duplicates.DuplicateReason
import com.unchunks.echomark.domain.repository.DuplicateMatch
import com.unchunks.echomark.domain.repository.RelatedGroup
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.ui.duplicates.DuplicatesContent
import com.unchunks.echomark.ui.duplicates.DuplicatesTab
import com.unchunks.echomark.ui.duplicates.DuplicatesUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** 重複と関連の画面の見た目(候補・まとまり・空・探している間)。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DuplicatesScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val original = PreviewSamples.urlBookmark
    private val copy = PreviewSamples.urlBookmark.copy(
        id = 11,
        contentUri = "https://example.com/articles/compose-performance/?utm_source=newsletter",
        title = "https://example.com/articles/compose-performance/?utm_source=newsletter",
        summary = null,
        tags = emptyList(),
        isFavorite = false
    )
    private val similar = PreviewSamples.textBookmark.copy(id = 12)

    private val state = DuplicatesUiState(
        isLoading = false,
        duplicates = listOf(
            DuplicateMatch(original, copy, DuplicateReason.SAME_URL, distance = null, suggestedKeepId = original.id),
            DuplicateMatch(original, similar, DuplicateReason.SIMILAR_CONTENT, distance = 0.042, suggestedKeepId = original.id)
        ),
        groups = listOf(
            RelatedGroup("Compose", listOf(original, copy, similar)),
            RelatedGroup("読書", listOf(PreviewSamples.textBookmark, similar.copy(id = 13), similar.copy(id = 14)))
        ),
        embeddedCount = 8,
        totalCount = 12,
        indexIncomplete = true
    )

    private fun content(state: DuplicatesUiState, tab: DuplicatesTab = DuplicatesTab.DUPLICATES): @androidx.compose.runtime.Composable () -> Unit = {
        DuplicatesContent(
            uiState = state,
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onOpenBookmark = {},
            onRefresh = {},
            onSensitivity = {},
            onMerge = { _, _ -> },
            onKeepBoth = {},
            initialTab = tab
        )
    }

    @Test
    fun candidates() = screenshot.captureScreenLightDark("duplicates_candidates", content = content(state))

    @Test
    fun groups() = screenshot.captureScreenLightDark("duplicates_groups", content = content(state, DuplicatesTab.GROUPS))

    @Test
    fun empty() = screenshot.captureScreenLightDark(
        "duplicates_empty",
        content = content(DuplicatesUiState(isLoading = false))
    )

    @Test
    fun loading() = screenshot.captureScreenLightDark("duplicates_loading", content = content(DuplicatesUiState()))
}
