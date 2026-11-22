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
