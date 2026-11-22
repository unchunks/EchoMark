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
import com.unchunks.echomark.ui.podcast.PodcastPlayerCallbacks
import com.unchunks.echomark.ui.podcast.PodcastPlayerContent
import com.unchunks.echomark.ui.podcast.PodcastPlayerUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

/** ポッドキャストを作る画面・エピソードの一覧(できた・作成中・失敗・空)・再生の画面の見た目。 */
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

    @Test
    fun player() {
        val lines = listOf(
            EpisodeLine(PodcastSpeaker.HOST, "こんにちは。EchoMark ポッドキャストです。今回は、保存したブックマークから3件を紹介します。", "", null, 0),
            EpisodeLine(PodcastSpeaker.GUEST, "よろしくお願いします。", "", null, 6_000),
            EpisodeLine(PodcastSpeaker.HOST, "1件目は、Zennの記事「Kotlin 2.3 の新機能」です。", "", 1, 8_000),
            EpisodeLine(PodcastSpeaker.HOST, "Kotlin 2.3 では、K2 コンパイラーが既定になり、ビルドが速くなりました。", "", 1, 12_000),
            EpisodeLine(PodcastSpeaker.GUEST, "速くなったのはうれしいですね。どれくらい変わったんですか?", "", 1, 19_000),
            EpisodeLine(PodcastSpeaker.HOST, "大きなプロジェクトでは、2倍近く速くなったそうです。", "", 1, 24_000)
        )
        screenshot.captureScreenLightDark("podcast_player") {
            PodcastPlayerContent(
                uiState = PodcastPlayerUiState(
                    isLoading = false,
                    episode = ready.copy(lines = lines),
                    isCurrent = true,
                    isPlaying = true,
                    positionMs = 13_500,
                    durationMs = 312_000,
                    speed = 1.25f,
                    currentLine = 3
                ),
                snackbarHostState = SnackbarHostState(),
                callbacks = PodcastPlayerCallbacks()
            )
        }
    }
}
