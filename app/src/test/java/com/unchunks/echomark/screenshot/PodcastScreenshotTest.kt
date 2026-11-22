package com.unchunks.echomark.screenshot

import androidx.compose.material3.SnackbarHostState
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.podcast.EpisodeLine
import com.unchunks.echomark.domain.podcast.EpisodeSource
import com.unchunks.echomark.domain.podcast.EpisodeStatus
import com.unchunks.echomark.domain.podcast.PodcastEpisode
import com.unchunks.echomark.domain.podcast.PodcastFormat
import com.unchunks.echomark.domain.podcast.PodcastProgress
import com.unchunks.echomark.domain.podcast.PodcastSpeaker
import com.unchunks.echomark.domain.podcast.ReadingEntry
import com.unchunks.echomark.domain.podcast.ScriptGenerator
import com.unchunks.echomark.ui.podcast.PodcastCreateContent
import com.unchunks.echomark.ui.podcast.PodcastCreateUiState
import com.unchunks.echomark.ui.podcast.PodcastEpisodesCallbacks
import com.unchunks.echomark.ui.podcast.PodcastEpisodesContent
import com.unchunks.echomark.ui.podcast.PodcastEpisodesUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

/** ポッドキャストを作る画面と、エピソードの一覧(できた・作成中・失敗・空)の見た目。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PodcastScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val zone = ZoneId.of("Asia/Tokyo")

    // 2026-11-20 09:00(日本時間)
    private val createdAt = 1_795_132_800_000L

    private val ready = PodcastEpisode(
        id = "ready",
        title = "Kotlin 2.3 の新機能 ほか2件",
        createdAt = createdAt,
        format = PodcastFormat.DUO,
        sources = listOf(
            EpisodeSource(1, "Kotlin 2.3 の新機能", "Zenn", "https://zenn.dev/a"),
            EpisodeSource(2, "日本橋の新しい図書館", "東京新聞", "https://example.com/b"),
            EpisodeSource(3, "買い物メモ", null, null)
        ),
        status = EpisodeStatus.READY,
        lines = listOf(EpisodeLine(PodcastSpeaker.HOST, "こんにちは。", "こんにちは。", null, 0)),
        readings = listOf(ReadingEntry("日本橋", "にほんばし"), ReadingEntry("Anthropic", "アンソロピック")),
        generator = ScriptGenerator.AI,
        audioFile = "episode.wav",
        durationMs = 312_000,
        sizeBytes = 14_900_000
    )
    private val generating = ready.copy(id = "gen", title = "生成 AI のニュース", createdAt = createdAt + 3_600_000, status = EpisodeStatus.GENERATING,
        sources = ready.sources.take(1), durationMs = null, sizeBytes = null, format = PodcastFormat.SOLO)
    private val failed = ready.copy(id = "fail", title = "読みたい記事", status = EpisodeStatus.FAILED, durationMs = null, sizeBytes = null,
        failureReason = "端末に入っている日本語の声がありません。端末の設定で日本語の音声データをダウンロードしてください。")

    @Test
    fun create() = screenshot.captureScreenLightDark("podcast_create") {
        PodcastCreateContent(
            uiState = PodcastCreateUiState(
                isLoading = false,
                titles = listOf("Kotlin 2.3 の新機能", "日本橋の新しい図書館", "買い物メモ"),
                format = PodcastFormat.DUO
            ),
            snackbarHostState = SnackbarHostState(),
            onClose = {},
            onFormatChange = {},
            onCreate = {}
        )
    }

    @Test
    fun episodes() = screenshot.captureScreenLightDark("podcast_episodes") {
        PodcastEpisodesContent(
            uiState = PodcastEpisodesUiState(
                isLoading = false,
                episodes = listOf(generating, ready, failed),
                progress = mapOf("gen" to PodcastProgress.WritingScript(1, 1))
            ),
            snackbarHostState = SnackbarHostState(),
            callbacks = PodcastEpisodesCallbacks(),
            zone = zone
        )
    }

    @Test
    fun empty() = screenshot.captureScreenLightDark("podcast_episodes_empty") {
        PodcastEpisodesContent(
            uiState = PodcastEpisodesUiState(isLoading = false),
            snackbarHostState = SnackbarHostState(),
            callbacks = PodcastEpisodesCallbacks(),
            zone = zone
        )
    }
}
