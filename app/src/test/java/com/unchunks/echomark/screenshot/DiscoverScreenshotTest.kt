package com.unchunks.echomark.screenshot

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.data.discover.DiscoveredResource
import com.unchunks.echomark.data.discover.DiscoveryStep
import com.unchunks.echomark.data.discover.ResourceCandidate
import com.unchunks.echomark.data.discover.ResourceSource
import com.unchunks.echomark.ui.discover.DiscoverContent
import com.unchunks.echomark.ui.discover.DiscoverUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** 資料を探す画面(探す前・AI が選んでいる途中・結果)の見た目。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoverScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val resources = listOf(
        DiscoveredResource(
            ResourceCandidate(
                "https://qiita.com/a", "Kotlin コルーチン入門 - 基本から構造化された並行性まで",
                "launch と async の違い、CoroutineScope の考え方、キャンセルと例外の扱いを、サンプルコードで順に説明します。",
                ResourceSource.ARTICLE, siteName = "Qiita"
            ),
            isRecommended = true,
            reason = "基本から順にまとまっていて最初に読むのに向く"
        ),
        DiscoveredResource(
            ResourceCandidate("https://www.youtube.com/watch?v=abcdefghijk", "10分でわかる Kotlin コルーチン", "", ResourceSource.VIDEO, siteName = "Android開発チャンネル"),
            isRecommended = true,
            reason = "動画で全体の流れをつかめる",
            isSaved = true
        ),
        DiscoveredResource(
            ResourceCandidate("https://ja.wikipedia.org/wiki/コルーチン", "コルーチン", "コルーチンはプログラミングの構造の一種。サブルーチンと違い、状態を管理しつつ処理を途中で抜けて、後で再開できる。", ResourceSource.ENCYCLOPEDIA, siteName = "Wikipedia"),
            isRecommended = false
        )
    )

    @Composable
    private fun Content(state: DiscoverUiState) {
        DiscoverContent(
            uiState = state,
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onOpenAiSettings = {},
            onTopicChange = {},
            onToggleSource = {},
            onSearch = {},
            onSearchExample = {},
            onCancel = {},
            onToggleSelection = {},
            onSave = {}
        )
    }

    @Test
    fun intro() = screenshot.captureScreenLightDark("discover_intro") {
        Content(DiscoverUiState())
    }

    @Test
    fun selecting() = screenshot.captureScreenLightDark("discover_selecting") {
        Content(DiscoverUiState(topic = "Kotlin コルーチン 入門", step = DiscoveryStep.SELECTING, hasSearched = true))
    }

    @Test
    fun results() = screenshot.captureScreenLightDark("discover_results") {
        Content(
            DiscoverUiState(
                topic = "Kotlin コルーチン 入門",
                hasSearched = true,
                resources = resources,
                selectedUrls = setOf("https://qiita.com/a"),
                failedSources = setOf(ResourceSource.PAPER),
                sources = setOf(ResourceSource.ARTICLE, ResourceSource.VIDEO, ResourceSource.ENCYCLOPEDIA, ResourceSource.PAPER)
            )
        )
    }
}
