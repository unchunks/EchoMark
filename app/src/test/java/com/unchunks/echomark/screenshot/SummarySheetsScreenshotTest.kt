package com.unchunks.echomark.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.model.SummarySource
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.domain.model.SummaryVersion
import com.unchunks.echomark.ui.detail.SummaryEditSheetContent
import com.unchunks.echomark.ui.detail.SummaryHistorySheetContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/** 要約を直すシートと、要約の履歴を見比べるシート(#19・#20)。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SummarySheetsScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val now = PreviewSamples.NOW

    private val versions = listOf(
        SummaryVersion(3, 1, "Compose の再コンポジションを減らす方法を、計測の手順と合わせて自分の言葉で直した要約。", SummarySource.MANUAL, null, null, now - 60_000),
        SummaryVersion(2, 1, "・再コンポジションの原因は不安定な引数\n・remember と derivedStateOf で抑える\n・Layout Inspector で回数を計測する", SummarySource.AI, "Claude (Anthropic) claude-haiku-5-5", "日本語・箇条書き", now - 3_600_000),
        SummaryVersion(1, 1, "Jetpack Compose の再コンポジションを減らすコツを解説した記事。", SummarySource.AI, "端末内 AI(gemma-3-1b)", "日本語・文章", now - 86_400_000)
    )

    private val current = versions.first().summary

    @Test
    fun edit() = screenshot.captureLightDark("summary_edit_sheet") {
        Surface {
            SummaryEditSheetContent(text = current, onTextChange = {}, onCancel = {}, onSave = {})
        }
    }

    @Test
    fun editEmpty() = screenshot.captureLightDark("summary_edit_sheet_empty") {
        Surface {
            SummaryEditSheetContent(text = "", onTextChange = {}, onCancel = {}, onSave = {})
        }
    }

    @Test
    fun history() = screenshot.captureLightDark("summary_history_sheet") {
        Surface {
            Box {
                SummaryHistorySheetContent(
                    current = current, versions = versions, compareId = null, onCompare = {}, onRestore = {}, zone = ZoneOffset.UTC
                )
            }
        }
    }

    @Test
    fun historyCompare() = screenshot.captureLightDark("summary_history_sheet_compare") {
        Surface {
            Box {
                SummaryHistorySheetContent(
                    current = current, versions = versions, compareId = 2, onCompare = {}, onRestore = {}, zone = ZoneOffset.UTC
                )
            }
        }
    }
}
