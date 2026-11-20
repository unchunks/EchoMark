package com.unchunks.echomark.ui.bookmark

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkListDisplay
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 一覧のトップバー(スクロールで隠れたあとの戻り方・検索欄の入力)のテスト。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class BookmarkListTopBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun スクロールで隠れたトップバーが空状態への切り替えで戻る() {
        val many = (1L..30L).map { PreviewSamples.urlBookmark.copy(id = it, isFavorite = false) }
        val state = mutableStateOf(BookmarkListUiState(bookmarks = many, totalCount = many.size))
        composeRule.setContent {
            EchoMarkTheme {
                BookmarkListContent(
                    uiState = state.value,
                    snackbarHostState = SnackbarHostState(),
                    callbacks = BookmarkListCallbacks(),
                    nowMillis = PreviewSamples.NOW
                )
            }
        }
        // 縦に並ぶカードの一覧(横スクロールの絞り込みチップではない方)をスクロールする
        composeRule.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("ブックマーク").assertIsNotDisplayedOrMissing()

        // お気に入りで絞り込んだら 0 件だった(空状態はスクロールしない)
        composeRule.runOnIdle {
            state.value = BookmarkListUiState(filter = BookmarkFilter.FAVORITES, totalCount = many.size)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("ブックマーク").assertIsDisplayed()
    }

    @Test
    fun 表示の切り替えメニューで一覧の見せ方を選べる() {
        val chosen = mutableListOf<BookmarkListDisplay>()
        composeRule.setContent {
            EchoMarkTheme {
                BookmarkListContent(
                    uiState = BookmarkListUiState(bookmarks = PreviewSamples.all, totalCount = 4),
                    snackbarHostState = SnackbarHostState(),
                    callbacks = BookmarkListCallbacks(onDisplayChange = { chosen += it }),
                    nowMillis = PreviewSamples.NOW
                )
            }
        }

        composeRule.onNodeWithContentDescription("表示の切り替え(カード)").performClick()
        composeRule.onNodeWithText("コンパクト").assertIsDisplayed()
        composeRule.onNodeWithText("グリッド").performClick()

        assertEquals(listOf(BookmarkListDisplay.GRID), chosen)
    }

    @Test
    fun どの見せ方でも全部のブックマークが出て_タップで開ける() {
        val display = mutableStateOf(BookmarkListDisplay.CARD)
        val opened = mutableListOf<Long>()
        val bookmarks = listOf(
            PreviewSamples.urlBookmark.copy(id = 1, title = "一件目のタイトル"),
            PreviewSamples.textBookmark.copy(id = 2, title = "二件目のタイトル")
        )
        composeRule.setContent {
            EchoMarkTheme {
                BookmarkListContent(
                    uiState = BookmarkListUiState(bookmarks = bookmarks, display = display.value, totalCount = 2),
                    snackbarHostState = SnackbarHostState(),
                    callbacks = BookmarkListCallbacks(onOpenBookmark = { opened += it }),
                    nowMillis = PreviewSamples.NOW
                )
            }
        }

        BookmarkListDisplay.entries.forEach { entry ->
            composeRule.runOnIdle { display.value = entry }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("一件目のタイトル").assertIsDisplayed()
            composeRule.onNodeWithText("二件目のタイトル").assertIsDisplayed()
            composeRule.onNodeWithText("二件目のタイトル").performClick()
        }

        assertEquals(listOf(2L, 2L, 2L), opened)
    }

    @Test
    fun 検索欄の入力は一覧の状態の反映を待たずに表示する() {
        val typed = mutableListOf<String>()
        composeRule.setContent {
            EchoMarkTheme {
                // ViewModel の uiState がまだ追いついていない(検索語が空のまま)状態
                BookmarkListContent(
                    uiState = BookmarkListUiState(isSearchActive = true),
                    snackbarHostState = SnackbarHostState(),
                    callbacks = BookmarkListCallbacks(onSearchQueryChange = { typed += it }),
                    nowMillis = PreviewSamples.NOW
                )
            }
        }

        composeRule.onNode(hasSetTextAction()).performTextInput("ラーメン")
        composeRule.waitForIdle()

        // 入力した文字が古い検索語(空)で上書きされない
        composeRule.onNode(hasSetTextAction()).assertTextEquals("ラーメン", includeEditableText = true)
        assertEquals("ラーメン", typed.last())

        composeRule.onNodeWithContentDescription("検索語を消す").performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        assertEquals("", typed.last())
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsNotDisplayedOrMissing() {
        if (fetchSemanticsNodes().isNotEmpty()) assertIsNotDisplayed()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.fetchSemanticsNodes() =
        runCatching { listOf(fetchSemanticsNode()) }.getOrDefault(emptyList())
}
