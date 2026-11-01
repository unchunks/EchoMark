package com.unchunks.echomark.ui.detail

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.bookmark.model.ReadingBlock
import com.unchunks.echomark.domain.bookmark.model.ReadingFont
import com.unchunks.echomark.domain.bookmark.model.ReadingFontSize
import com.unchunks.echomark.domain.bookmark.model.ReadingStyle
import com.unchunks.echomark.domain.bookmark.model.ReadingTheme
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 読書モード(#59)の表示と、見た目の変更・閉じる操作のテスト。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class ReadingModeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val blocks = listOf(
        ReadingBlock.Paragraph("最初の段落です。"),
        ReadingBlock.Heading("文字起こし"),
        ReadingBlock.Paragraph("二つ目の段落です。")
    )

    @Test
    fun タイトル_出どころと読む時間_本文を出し_閉じるで戻る() {
        var closed = 0
        composeRule.setContent {
            EchoMarkTheme {
                ReadingModeContent(
                    title = "記事のタイトル", source = "example.com", readingMinutes = 4, blocks = blocks,
                    style = ReadingStyle(), highlightTerms = emptyList(), onStyleChange = {}, onClose = { closed++ }
                )
            }
        }

        composeRule.onNodeWithText("記事のタイトル").assertIsDisplayed()
        composeRule.onNodeWithText("example.com · 約4分で読めます").assertIsDisplayed()
        composeRule.onNodeWithText("最初の段落です。").assertIsDisplayed()
        composeRule.onNodeWithText("文字起こし").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("読書モードを閉じる").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun 文字の大きさと書体と背景をシートで変えられる() {
        val style = mutableStateOf(ReadingStyle())
        val changes = mutableListOf<ReadingStyle>()
        composeRule.setContent {
            EchoMarkTheme {
                ReadingModeContent(
                    title = "記事", source = "", readingMinutes = null, blocks = blocks,
                    style = style.value, highlightTerms = emptyList(),
                    onStyleChange = {
                        changes += it
                        style.value = it
                    },
                    onClose = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("文字の大きさ・背景・書体").performClick()
        composeRule.onNodeWithText("特大").performClick()
        composeRule.onNodeWithText("明朝体").performClick()
        composeRule.onNodeWithText("黒").performClick()
        composeRule.waitForIdle()

        assertEquals(
            listOf(
                ReadingStyle(fontSize = ReadingFontSize.XLARGE),
                ReadingStyle(fontSize = ReadingFontSize.XLARGE, font = ReadingFont.SERIF),
                ReadingStyle(ReadingFontSize.XLARGE, ReadingTheme.DARK, ReadingFont.SERIF)
            ),
            changes
        )
    }

    @Test
    fun 検索語で開いたときは本文の一致を強調して出す() {
        composeRule.setContent {
            EchoMarkTheme {
                ReadingModeContent(
                    title = "記事", source = "", readingMinutes = null, blocks = blocks,
                    style = ReadingStyle(), highlightTerms = listOf("二つ目"), onStyleChange = {}, onClose = {}
                )
            }
        }

        // 強調しても文字は同じ(装飾が付くだけ)で、本文は出る
        composeRule.onNodeWithText("二つ目の段落です。").assertIsDisplayed()
    }
}
