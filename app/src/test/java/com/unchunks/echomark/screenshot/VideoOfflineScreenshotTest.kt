package com.unchunks.echomark.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.video.OfflineVideo
import com.unchunks.echomark.domain.video.SavedVideoFile
import com.unchunks.echomark.domain.video.SavedVideoListItem
import com.unchunks.echomark.domain.video.VideoDownloadSettings
import com.unchunks.echomark.domain.video.VideoDownloadWork
import com.unchunks.echomark.ui.video.OfflineVideosContent
import com.unchunks.echomark.ui.video.OfflineVideosUiState
import com.unchunks.echomark.domain.video.VideoOfflineStage
import com.unchunks.echomark.domain.video.VideoOfflineWork
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.ui.detail.BookmarkDetailCallbacks
import com.unchunks.echomark.ui.detail.BookmarkDetailContent
import com.unchunks.echomark.ui.detail.BookmarkDetailUiState
import com.unchunks.echomark.ui.video.VideoOfflineCallbacks
import com.unchunks.echomark.ui.video.VideoOfflineSection
import com.unchunks.echomark.ui.video.VideoOfflineUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 動画のブックマークの「オフラインで見る」(保存前・保存中・保存後)と、詳細画面での見え方。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoOfflineScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val video = Bookmark(
        id = 301,
        type = BookmarkType.URL,
        contentUri = "https://www.youtube.com/watch?v=abcdefghijk",
        title = "Kotlin コルーチン入門 — 構造化された並行性を30分で",
        siteName = "YouTube",
        imageUrl = "https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg",
        summary = "コルーチンのスコープとキャンセルの伝わり方を、例を使って説明している。launch と async の違い、" +
            "SupervisorJob を使う場面、Flow での例外の扱いの順に進む。",
        content = "チャンネル: Android 勉強会\n\n構造化された並行性の考え方を、短い例で説明します。\n\n" +
            "字幕:\nこんにちは。今日はコルーチンのスコープの話をします。\nまず launch から見ていきましょう。",
        createdAt = PreviewSamples.NOW - 3 * 60 * 60 * 1000L,
        lastAccessedAt = PreviewSamples.NOW,
        aiStatus = AiStatus.DONE
    )

    private val savedAt = PreviewSamples.NOW - 30 * 60 * 1000L

    @Test
    fun sectionStates() = screenshot.captureLightDark("video_offline_section_states") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            VideoOfflineSection(
                bookmark = video.copy(content = "チャンネル: Android 勉強会\n\n説明文だけ"),
                state = VideoOfflineUiState(),
                callbacks = VideoOfflineCallbacks()
            )
            VideoOfflineSection(
                bookmark = video,
                state = VideoOfflineUiState(work = VideoOfflineWork.Running(VideoOfflineStage.TRANSCRIPT)),
                callbacks = VideoOfflineCallbacks()
            )
            VideoOfflineSection(
                bookmark = video,
                state = VideoOfflineUiState(
                    saved = OfflineVideo(bookmarkId = video.id, thumbnailPath = "attachments/thumb.jpg", savedAt = savedAt),
                    thumbnailFile = File("thumb.jpg")
                ),
                callbacks = VideoOfflineCallbacks()
            )
            // 字幕が無い動画(Vimeo など)で、要約を作っている途中・サムネイルを保存できなかったとき
            VideoOfflineSection(
                bookmark = video.copy(
                    contentUri = "https://vimeo.com/123456",
                    content = "説明文だけ",
                    summary = null,
                    aiStatus = AiStatus.PROCESSING
                ),
                state = VideoOfflineUiState(saved = OfflineVideo(bookmarkId = video.id, thumbnailPath = null, savedAt = savedAt)),
                callbacks = VideoOfflineCallbacks()
            )
        }
    }

    private val peerTube = video.copy(
        id = 302,
        contentUri = "https://peertube.example/w/9c9de5e8xQNXhHR4Fvu8Qa",
        siteName = "PeerTube",
        title = "自転車の旅 — 瀬戸内の島をめぐる"
    )

    private val savedVideo = SavedVideoFile(
        path = "offline_videos/302-a.mp4",
        mimeType = "video/mp4",
        size = 284_000_000L,
        sourceUrl = "https://peertube.example/download/videos/720.mp4",
        label = "720p",
        savedAt = savedAt
    )

    @Test
    fun videoFileStates() = screenshot.captureLightDark("video_offline_file_states") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // PeerTube: 字幕などは保存済み、動画はまだ
            VideoOfflineSection(
                bookmark = peerTube,
                state = VideoOfflineUiState(
                    saved = OfflineVideo(bookmarkId = peerTube.id, thumbnailPath = "attachments/thumb.jpg", savedAt = savedAt),
                    thumbnailFile = File("thumb.jpg")
                ),
                callbacks = VideoOfflineCallbacks()
            )
            // 動画のファイルへの直接のリンク: 落としている途中
            VideoOfflineSection(
                bookmark = peerTube.copy(contentUri = "https://example.com/media/lecture.mp4", content = null),
                state = VideoOfflineUiState(download = VideoDownloadWork.Downloading(120_000_000L, 340_000_000L)),
                callbacks = VideoOfflineCallbacks()
            )
            // 直接のリンク: Wi-Fi を待っている
            VideoOfflineSection(
                bookmark = peerTube.copy(contentUri = "https://example.com/media/lecture.mp4", content = null),
                state = VideoOfflineUiState(download = VideoDownloadWork.Waiting),
                callbacks = VideoOfflineCallbacks()
            )
            // PeerTube: 動画も保存済み
            VideoOfflineSection(
                bookmark = peerTube,
                state = VideoOfflineUiState(
                    saved = OfflineVideo(peerTube.id, thumbnailPath = "attachments/thumb.jpg", savedAt = savedAt, video = savedVideo),
                    thumbnailFile = File("thumb.jpg"),
                    videoFile = File("302-a.mp4")
                ),
                callbacks = VideoOfflineCallbacks()
            )
        }
    }

    @Test
    fun offlineVideosScreen() = screenshot.captureScreenLightDark("video_offline_storage_screen") {
        OfflineVideosContent(
            uiState = OfflineVideosUiState(
                isLoading = false,
                settings = VideoDownloadSettings(),
                items = listOf(
                    SavedVideoListItem(302, "自転車の旅 — 瀬戸内の島をめぐる", "offline_videos/302-a.mp4", 284_000_000L, "720p", savedAt),
                    SavedVideoListItem(303, "講義 第3回 線形代数の基礎", "offline_videos/303-b.mp4", 512_000_000L, null, savedAt - 86_400_000L)
                ),
                usedBytes = 796_000_000L,
                freeBytes = 18_500_000_000L
            ),
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onWifiOnlyChange = {},
            onMaxTotalBytesChange = {},
            onPlay = {},
            onDelete = {},
            onDeleteAll = {}
        )
    }

    @Test
    fun offlineVideosScreenEmpty() = screenshot.captureScreenLightDark("video_offline_storage_screen_empty") {
        OfflineVideosContent(
            uiState = OfflineVideosUiState(isLoading = false, settings = VideoDownloadSettings(wifiOnly = false)),
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onWifiOnlyChange = {},
            onMaxTotalBytesChange = {},
            onPlay = {},
            onDelete = {},
            onDeleteAll = {}
        )
    }

    @Test
    fun detailSaved() = screenshot.captureScreenLightDark("video_offline_detail_saved", heightDp = 1400) {
        BookmarkDetailContent(
            uiState = BookmarkDetailUiState(isLoading = false, bookmark = video),
            snackbarHostState = SnackbarHostState(),
            callbacks = BookmarkDetailCallbacks(
                videoOfflineState = VideoOfflineUiState(
                    saved = OfflineVideo(bookmarkId = video.id, thumbnailPath = "attachments/thumb.jpg", savedAt = savedAt),
                    // 保存したサムネイル(画像は仮の単色で描かれる)
                    thumbnailFile = File("thumb.jpg")
                )
            ),
            nowMillis = PreviewSamples.NOW
        )
    }
}
