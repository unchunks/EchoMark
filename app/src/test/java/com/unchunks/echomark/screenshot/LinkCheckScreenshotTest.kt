package com.unchunks.echomark.screenshot

import androidx.compose.material3.SnackbarHostState
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.linkcheck.LinkCheckState
import com.unchunks.echomark.domain.repository.DeadLink
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.ui.detail.BookmarkDetailCallbacks
import com.unchunks.echomark.ui.detail.BookmarkDetailContent
import com.unchunks.echomark.ui.detail.BookmarkDetailUiState
import com.unchunks.echomark.ui.detail.DeadLinkSection
import com.unchunks.echomark.ui.linkcheck.BrokenLinksContent
import com.unchunks.echomark.ui.linkcheck.BrokenLinksUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** リンク切れの一覧と、詳細画面のリンク切れの知らせの見た目。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LinkCheckScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val dead = LinkCheckState(
        checkedAt = PreviewSamples.NOW, failureCount = 3, firstFailedAt = PreviewSamples.NOW - 172_800_000L,
        deadAt = PreviewSamples.NOW, lastReason = "ページが見つかりません(HTTP 404)"
    )
    private val withCopy = PreviewSamples.urlBookmark.copy(snapshotPath = "snapshots/a.mhtml", snapshotAt = PreviewSamples.NOW)
    private val links = listOf(
        DeadLink(withCopy, PreviewSamples.NOW, 3, "ページが見つかりません(HTTP 404)"),
        DeadLink(PreviewSamples.textBookmark.copy(id = 9, contentUri = "https://old.example.org/post"), PreviewSamples.NOW - 1000, 4, "サイトが見つかりません")
    )

    private fun list(state: BrokenLinksUiState): @androidx.compose.runtime.Composable () -> Unit = {
        BrokenLinksContent(
            uiState = state, snackbarHostState = SnackbarHostState(), onBack = {}, onOpenBookmark = {}, onEnabledChange = {},
            onOpenOfflineCopy = {}, onSearchArchive = {}, onRecheck = {}, onMute = {}
        )
    }

    @Test
    fun list() = screenshot.captureScreenLightDark("broken_links", content = list(BrokenLinksUiState(isLoading = false, links = links)))

    @Test
    fun listEmpty() = screenshot.captureScreenLightDark("broken_links_empty", content = list(BrokenLinksUiState(isLoading = false)))

    @Test
    fun listEmptyDisabled() = screenshot.captureScreenLightDark(
        "broken_links_disabled", content = list(BrokenLinksUiState(isLoading = false, enabled = false))
    )

    @Test
    fun bannerWithOfflineCopy() = screenshot.captureScreenLightDark("broken_link_banner_copy", heightDp = 400) {
        DeadLinkSection(state = dead, hasOfflineCopy = true, checking = false, onOpenOfflineCopy = {}, onSearchArchive = {}, onRecheck = {}, onMute = {})
    }

    @Test
    fun bannerWithoutOfflineCopy() = screenshot.captureScreenLightDark("broken_link_banner_nocopy", heightDp = 400) {
        DeadLinkSection(state = dead, hasOfflineCopy = false, checking = true, onOpenOfflineCopy = {}, onSearchArchive = {}, onRecheck = {}, onMute = {})
    }

    @Test
    fun inDetail() = screenshot.captureScreenLightDark("broken_link_in_detail", heightDp = 1400) {
        BookmarkDetailContent(
            uiState = BookmarkDetailUiState(isLoading = false, bookmark = withCopy, linkState = dead),
            snackbarHostState = SnackbarHostState(),
            callbacks = BookmarkDetailCallbacks(),
            nowMillis = PreviewSamples.NOW
        )
    }
}
