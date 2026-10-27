package com.unchunks.echomark.ui.bookmark

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.model.Tag
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 絞り込みのチップの並び(タグの絞り込みを「すべて解除」で一度に外せること)のテスト。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class BookmarkListFilterBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val tags = (1L..12L).map { Tag(it, "タグ$it") }

    @Test
    fun タグで絞り込んでいる間だけすべて解除を出し押すと絞り込みを外す() {
        val selected = mutableListOf<Long?>()
        val state = mutableStateOf(BookmarkListUiState(bookmarks = PreviewSamples.all, allTags = tags, totalCount = 4))
        composeRule.setContent {
            EchoMarkTheme {
                BookmarkListContent(
                    uiState = state.value,
                    snackbarHostState = SnackbarHostState(),
                    callbacks = BookmarkListCallbacks(onTagSelected = {
                        selected += it
                        // ViewModel と同じく、選んだタグを先頭へ移す
                        state.value = state.value.copy(
                            selectedTagId = it,
                            allTags = tags.sortedByDescending { tag -> tag.id == it }
                        )
                    }),
                    nowMillis = PreviewSamples.NOW
                )
            }
        }
        composeRule.onNodeWithText("すべて解除").assertDoesNotExist()

        // 右へスクロールして後ろのタグを選ぶ(選んだチップは先頭へ移り、画面の外になる)
        composeRule.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .performScrollToNode(hasText("#タグ12"))
        composeRule.onNodeWithText("#タグ12").performClick()
        composeRule.waitForIdle()

        // スクロールし直さなくても、右端の「すべて解除」で一度に外せる
        composeRule.onNodeWithContentDescription("タグの絞り込みをすべて解除").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(12L, null), selected)
        composeRule.onNodeWithText("すべて解除").assertDoesNotExist()
    }
}
