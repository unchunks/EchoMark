package com.unchunks.echomark.ui.settings.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.llm.ondevice.EngineBackend
import com.unchunks.echomark.data.ai.model.CatalogDownloadState
import com.unchunks.echomark.data.ai.model.InstalledModels
import com.unchunks.echomark.data.ai.model.LocalModelInfo
import com.unchunks.echomark.data.ai.model.ModelCatalog
import com.unchunks.echomark.data.ai.model.ModelCatalogEntry
import com.unchunks.echomark.data.ai.model.PublishedModelPerformance
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** AI 設定の「おすすめのモデル」。 */
@RunWith(AndroidJUnit4::class)
class AiSettingsCatalogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val free = ModelCatalog.entries.first { !it.requiresAgreement }
    private val gated = ModelCatalog.entries.first { it.requiresAgreement }
    private val downloads = mutableListOf<ModelCatalogEntry>()
    private var cancels = 0

    private fun setContent(
        entries: List<ModelCatalogEntry> = listOf(free),
        installed: InstalledModels = InstalledModels(),
        state: CatalogDownloadState = CatalogDownloadState.Idle
    ) {
        composeRule.setContent {
            EchoMarkTheme {
                // 画面と同じく、カードを縦に並べる
                Column {
                    ModelCatalogSection(entries, installed, state, onDownload = { downloads += it }, onCancel = { cancels++ })
                }
            }
        }
    }

    @Test
    fun 確認してからダウンロードする() {
        setContent()

        composeRule.onNodeWithText("ダウンロード").performClick()
        composeRule.onNodeWithText("「${free.name}」をダウンロードしますか?").assertExists()
        assertTrue("押しただけでは始めない", downloads.isEmpty())

        composeRule.onAllNodesWithText("ダウンロード").filter(hasAnyAncestor(isDialog())).onFirst().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(free), downloads)
    }

    @Test
    fun 同意が要るモデルはダウンロードボタンを出さず入手方法を伝える() {
        setContent(entries = listOf(gated))

        composeRule.onNodeWithText("ダウンロード").assertDoesNotExist()
        composeRule.onNodeWithText("利用規約への同意が必要", substring = true).assertExists()
    }

    @Test
    fun 取り込み済みのモデルはそう伝える() {
        val model = LocalModelInfo("local_llm_1.litertlm", free.fileName, free.sizeBytes, 0)
        setContent(installed = InstalledModels(listOf(model)))

        composeRule.onNodeWithText("取り込み済み").assertExists()
        composeRule.onNodeWithText("ダウンロード").assertDoesNotExist()
    }

    @Test
    fun ダウンロード中は進み具合と中止を出し_ほかのモデルは始められない() {
        val other = ModelCatalog.entries.first { !it.requiresAgreement && it != free }
        setContent(
            entries = listOf(free, other),
            state = CatalogDownloadState.Downloading(free, downloadedBytes = 1024, waitingForNetwork = false)
        )

        composeRule.onNodeWithText("ダウンロードを中止").performClick()
        composeRule.waitForIdle()
        assertEquals(1, cancels)
        composeRule.onNodeWithText("ダウンロード").assertIsNotEnabled()
    }

    @Test
    fun Wi_Fiを待っていることを伝える() {
        setContent(state = CatalogDownloadState.Downloading(free, 0, waitingForNetwork = true))

        composeRule.onNodeWithText("Wi-Fi の接続を待っています").assertExists()
    }

    @Test
    fun 失敗の理由を出してやり直せる() {
        setContent(state = CatalogDownloadState.Failed(free, "ダウンロードできませんでした"))

        composeRule.onNodeWithText("ダウンロードできませんでした").assertExists()
        composeRule.onNodeWithText("ダウンロード").performClick()
        composeRule.onNodeWithText("「${free.name}」をダウンロードしますか?").assertExists()
    }

    @Test
    fun 取り込み済みかはファイル名と大きさで見る() {
        val same = LocalModelInfo("a.litertlm", free.fileName, free.sizeBytes, 0)
        val otherSize = LocalModelInfo("b.litertlm", free.fileName, 1, 0)

        assertEquals(same, InstalledModels(listOf(same)).modelFor(free))
        assertEquals(null, InstalledModels(listOf(otherSize)).modelFor(free))
    }

    @Test
    fun 公開の性能は端末と演算装置とメモリを1行で伝える() {
        assertEquals(
            "公開の測定例(Galaxy の CPU): 出力 12.3 トークン/秒・メモリ 2000 MB",
            publishedPerformanceSummary(PublishedModelPerformance("Galaxy", EngineBackend.CPU, 12.34, 2000))
        )
        assertEquals(
            "公開の測定例(Pixel の GPU): 出力 35.0 トークン/秒",
            publishedPerformanceSummary(PublishedModelPerformance("Pixel", EngineBackend.GPU, 35.0, null))
        )
    }
}
