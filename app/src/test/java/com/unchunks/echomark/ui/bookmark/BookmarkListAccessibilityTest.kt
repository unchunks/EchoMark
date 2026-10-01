package com.unchunks.echomark.ui.bookmark

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * スワイプの代わりの TalkBack のカスタムアクション(アーカイブ・削除)が、TalkBack がフォーカスするカード
 * (クリックできるノード)に付いていること。フォーカスされないノードに付けると、操作の一覧に出ない。
 */
@RunWith(AndroidJUnit4::class)
class BookmarkListAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun カードのクリックできるノードにアーカイブと削除のカスタムアクションがある() {
        val bookmark = PreviewSamples.urlBookmark
        val archived = mutableListOf<Pair<Bookmark, Boolean>>()
        val deleted = mutableListOf<Bookmark>()
        composeRule.setContent {
            EchoMarkTheme {
                BookmarkListContent(
                    uiState = BookmarkListUiState(bookmarks = listOf(bookmark), totalCount = 1),
                    snackbarHostState = SnackbarHostState(),
                    callbacks = BookmarkListCallbacks(
                        onSetArchived = { b, value -> archived += b to value },
                        onDelete = { deleted += it }
                    ),
                    nowMillis = PreviewSamples.NOW
                )
            }
        }

        val card = composeRule.onNode(hasClickAction() and hasText(bookmark.title, substring = true)).fetchSemanticsNode()
        val actions = card.config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertEquals(listOf("アーカイブ", "削除"), actions.map { it.label })

        composeRule.runOnIdle { actions.forEach { it.action() } }
        assertEquals(listOf(bookmark to true), archived)
        assertEquals(listOf(bookmark), deleted)
    }
}
