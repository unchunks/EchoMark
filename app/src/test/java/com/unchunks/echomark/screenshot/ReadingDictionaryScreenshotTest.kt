package com.unchunks.echomark.screenshot

import androidx.compose.material3.SnackbarHostState
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.podcast.ReadingEntry
import com.unchunks.echomark.domain.podcast.SpeechNormalizer
import com.unchunks.echomark.ui.podcast.ReadingDictionaryContent
import com.unchunks.echomark.ui.podcast.ReadingDictionaryUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** ポッドキャストの読みの辞書(入れた読みあり・なし)の見た目。読みを確かめる欄には、整えた文を出す。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingDictionaryScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val entries = listOf(
        ReadingEntry("日本橋", "にほんばし"),
        ReadingEntry("Anthropic", "アンソロピック"),
        ReadingEntry("生成AI", "せいせいエーアイ")
    )

    @Test
    fun withEntries() {
        val sample = "2026/11/20 10:30 に日本橋で v2.1 を公開"
        screenshot.captureScreenLightDark("podcast_reading_dictionary") {
            ReadingDictionaryContent(
                uiState = ReadingDictionaryUiState(
                    isLoading = false,
                    entries = entries,
                    sample = sample,
                    samplePreview = SpeechNormalizer.normalize(sample, entries).plain
                ),
                snackbarHostState = SnackbarHostState(),
                onBack = {},
                onSave = { _, _, _ -> },
                onRemove = {},
                onSampleChange = {}
            )
        }
    }

    @Test
    fun empty() = screenshot.captureScreenLightDark("podcast_reading_dictionary_empty") {
        ReadingDictionaryContent(
            uiState = ReadingDictionaryUiState(isLoading = false),
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onSave = { _, _, _ -> },
            onRemove = {},
            onSampleChange = {}
        )
    }
}
