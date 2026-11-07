package com.unchunks.echomark.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.bookmark.model.EvidenceReport
import com.unchunks.echomark.domain.bookmark.model.SentenceEvidence
import com.unchunks.echomark.domain.model.SummaryLanguage
import com.unchunks.echomark.ui.detail.EvidenceBlock
import com.unchunks.echomark.ui.detail.SummaryTranslateSheetContent
import com.unchunks.echomark.ui.detail.TranslationState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** 要約の根拠の表示(#18)と、要約の翻訳のシート(#24)。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SummaryExtrasScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    @Test
    fun evidence() = screenshot.captureLightDark("summary_evidence") {
        Surface {
            Box(Modifier.padding(16.dp)) {
                EvidenceBlock(
                    EvidenceReport(
                        comparable = true,
                        items = listOf(
                            SentenceEvidence(
                                "不安定な型やラムダがあると不要な再コンポーズが起きる",
                                "不安定な型や毎回生成されるラムダがあると、不要な再コンポーズが起きます。",
                                0.82f
                            ),
                            SentenceEvidence("Compose は 2030 年に廃止される予定", null, 0.05f)
                        )
                    )
                )
            }
        }
    }

    @Test
    fun evidenceNotComparable() = screenshot.captureLightDark("summary_evidence_not_comparable") {
        Surface {
            Box(Modifier.padding(16.dp)) {
                EvidenceBlock(EvidenceReport(comparable = false, items = emptyList()))
            }
        }
    }

    @Test
    fun translateIdle() = screenshot.captureLightDark("summary_translate_idle") {
        Surface {
            SummaryTranslateSheetContent(state = TranslationState.Idle, onTranslate = {}, onCopy = {})
        }
    }

    @Test
    fun translateLoading() = screenshot.captureLightDark("summary_translate_loading") {
        Surface {
            SummaryTranslateSheetContent(state = TranslationState.Loading(SummaryLanguage.ENGLISH), onTranslate = {}, onCopy = {})
        }
    }

    @Test
    fun translateDone() = screenshot.captureLightDark("summary_translate_done") {
        Surface {
            SummaryTranslateSheetContent(
                state = TranslationState.Done(
                    SummaryLanguage.ENGLISH,
                    "・Only the parts whose state has changed are recomposed\n・Unstable parameters cause needless recomposition\n・Measure with Layout Inspector"
                ),
                onTranslate = {},
                onCopy = {}
            )
        }
    }

    @Test
    fun translateFailed() = screenshot.captureLightDark("summary_translate_failed") {
        Surface {
            SummaryTranslateSheetContent(
                state = TranslationState.Failed(SummaryLanguage.KOREAN, "端末内 AI モデルが未取り込みです。設定 > AI 設定 でモデルを取り込むか、クラウド API を選んでください"),
                onTranslate = {},
                onCopy = {}
            )
        }
    }
}
