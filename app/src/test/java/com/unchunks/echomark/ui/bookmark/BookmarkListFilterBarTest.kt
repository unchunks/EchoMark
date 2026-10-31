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
import com.unchunks.echomark.domain.bookmark.model.BookmarkQuery
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.CreatedPeriod
import com.unchunks.echomark.domain.bookmark.model.ReadState
import com.unchunks.echomark.domain.bookmark.model.TagMatch
import com.unchunks.echomark.domain.model.Tag
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 絞り込みのチップの並び(タグ・カテゴリを複数選べること、タグの組み合わせ方の切り替え、
 * タグとカテゴリの絞り込みを「すべて解除」で一度に外せること)のテスト。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class BookmarkListFilterBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val tags = (1L..12L).map { Tag(it, "タグ$it") }
    private val categories = listOf("技術", "ニュース")

    /** ViewModel と同じく、選んだものを先頭へ移して状態に反映する(選択の規則は [BookmarkQuery] のもの)。 */
    private fun setContent(
        calls: MutableList<String> = mutableListOf(),
        initial: BookmarkQuery = BookmarkQuery()
    ): androidx.compose.runtime.MutableState<BookmarkListUiState> {
        var query = initial
        fun state(): BookmarkListUiState = BookmarkListUiState(
            bookmarks = PreviewSamples.all,
            allTags = tags.sortedByDescending { it.id in query.tagIds },
            selectedTagIds = query.tagIds,
            tagMatch = query.tagMatch,
            allCategories = categories.sortedByDescending { name -> query.categories.any { it.equals(name, true) } },
            selectedCategories = query.categories,
            selectedTypes = query.types,
            period = query.period,
            readState = query.readState,
            totalCount = 4
        )
        val state = mutableStateOf(state())
        composeRule.setContent {
            EchoMarkTheme {
                BookmarkListContent(
                    uiState = state.value,
                    snackbarHostState = SnackbarHostState(),
                    callbacks = BookmarkListCallbacks(
                        onTagToggle = {
                            calls += "tag:$it"
                            query = query.toggleTag(it)
                            state.value = state()
                        },
                        onCategoryToggle = {
                            calls += "category:$it"
                            query = query.toggleCategory(it)
                            state.value = state()
                        },
                        onTypeToggle = {
                            calls += "type:$it"
                            query = query.toggleType(it)
                            state.value = state()
                        },
                        onReadStateToggle = {
                            calls += "read:$it"
                            query = query.toggleReadState(it)
                            state.value = state()
                        },
                        onPeriodToggle = {
                            calls += "period:$it"
                            query = query.togglePeriod(it)
                            state.value = state()
                        },
                        onTagMatchChange = {
                            calls += "match:$it"
                            query = query.copy(tagMatch = it)
                            state.value = state()
                        },
                        onClearFilters = {
                            calls += "clear"
                            query = query.clearConditions()
                            state.value = state()
                        }
                    ),
                    nowMillis = PreviewSamples.NOW
                )
            }
        }
        return state
    }

    private fun scrollChipsTo(text: String) {
        composeRule.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .performScrollToNode(hasText(text))
    }

    @Test
    fun タグで絞り込んでいる間だけすべて解除を出し押すと絞り込みを外す() {
        val calls = mutableListOf<String>()
        setContent(calls)
        composeRule.onNodeWithText("すべて解除").assertDoesNotExist()

        // 右へスクロールして後ろのタグを選ぶ(選んだチップは先頭へ移り、画面の外になる)
        scrollChipsTo("#タグ12")
        composeRule.onNodeWithText("#タグ12").performClick()
        composeRule.waitForIdle()

        // スクロールし直さなくても、右端の「すべて解除」で一度に外せる
        composeRule.onNodeWithContentDescription("タグ・カテゴリ・種類・期間・読んだかの絞り込みをすべて解除").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("tag:12", "clear"), calls)
        composeRule.onNodeWithText("すべて解除").assertDoesNotExist()
    }

    @Test
    fun タグを2つ選ぶと組み合わせ方の切り替えが出て_すべて解除で全部外れる() {
        val calls = mutableListOf<String>()
        setContent(calls)
        composeRule.onNodeWithText("すべて含む").assertDoesNotExist()

        scrollChipsTo("#タグ1")
        composeRule.onNodeWithText("#タグ1").performClick()
        composeRule.waitForIdle()
        // 1つだけのときは組み合わせ方を出さない
        composeRule.onNodeWithText("すべて含む").assertDoesNotExist()

        scrollChipsTo("#タグ5")
        composeRule.onNodeWithText("#タグ5").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("選んだ2個のタグを").assertIsDisplayed()
        composeRule.onNodeWithText("どれかを含む").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("タグ・カテゴリ・種類・期間・読んだかの絞り込みをすべて解除").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("tag:1", "tag:5", "match:ANY", "clear"), calls)
        composeRule.onNodeWithText("すべて含む").assertDoesNotExist()
        composeRule.onNodeWithText("すべて解除").assertDoesNotExist()
    }

    @Test
    fun カテゴリを選ぶとすべて解除が出て_タグと一緒に外れる() {
        val calls = mutableListOf<String>()
        setContent(calls)

        scrollChipsTo("ニュース")
        composeRule.onNodeWithContentDescription("カテゴリ「ニュース」").performClick()
        composeRule.waitForIdle()
        scrollChipsTo("#タグ3")
        composeRule.onNodeWithText("#タグ3").performClick()
        composeRule.waitForIdle()

        // カテゴリだけ・タグだけのどちらでも出る。ここでは両方選んでいる
        composeRule.onNodeWithContentDescription("タグ・カテゴリ・種類・期間・読んだかの絞り込みをすべて解除").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("category:ニュース", "tag:3", "clear"), calls)
        composeRule.onNodeWithText("すべて解除").assertDoesNotExist()
    }

    @Test
    fun 種類と期間を選んでいる間もチップとすべて解除を出し_押すと外れる() {
        val calls = mutableListOf<String>()
        setContent(calls, initial = BookmarkQuery(types = setOf(BookmarkType.URL), period = CreatedPeriod.PAST_WEEK, readState = ReadState.UNREAD)
        )

        scrollChipsTo("条件(3)")
        composeRule.onNodeWithText("条件(3)").assertIsDisplayed()
        scrollChipsTo("未読")
        composeRule.onNodeWithContentDescription("未読で絞り込み中。タップで外す").assertIsDisplayed()
        scrollChipsTo("リンク")
        composeRule.onNodeWithContentDescription("リンクで絞り込み中。タップで外す").assertIsDisplayed()
        scrollChipsTo("1週間以内")
        composeRule.onNodeWithContentDescription("1週間以内で絞り込み中。タップで外す").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("タグ・カテゴリ・種類・期間・読んだかの絞り込みをすべて解除").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("clear"), calls)
        composeRule.onNodeWithContentDescription("リンクで絞り込み中。タップで外す").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("1週間以内で絞り込み中。タップで外す").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("未読で絞り込み中。タップで外す").assertDoesNotExist()
    }

    @Test
    fun 種類と時期のシートで選ぶと条件に入り_チップから外せる() {
        val calls = mutableListOf<String>()
        setContent(calls)

        composeRule.onNodeWithContentDescription("種類・保存した時期・読んだかで絞り込む").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("画像").performClick()
        composeRule.onNodeWithText("1か月以内").performClick()
        composeRule.onNodeWithText("既読").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("type:IMAGE", "period:PAST_MONTH", "read:READ"), calls)

        composeRule.onNodeWithText("閉じる").performClick()
        composeRule.waitForIdle()
        scrollChipsTo("画像")
        composeRule.onNodeWithContentDescription("画像で絞り込み中。タップで外す").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals("type:IMAGE", calls.last())
    }

    @Test
    fun カテゴリだけを選んでいてもすべて解除を出す() {
        setContent(initial = BookmarkQuery(categories = setOf("技術")))

        composeRule.onNodeWithText("すべて解除").assertIsDisplayed()
        // 組み合わせ方は、タグを2つ以上選んだときだけ
        composeRule.onNodeWithText("すべて含む").assertDoesNotExist()
        assertEquals(TagMatch.ALL, BookmarkQuery().tagMatch)
    }
}
