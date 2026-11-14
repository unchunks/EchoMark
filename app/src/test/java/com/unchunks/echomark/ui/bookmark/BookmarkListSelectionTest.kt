package com.unchunks.echomark.ui.bookmark

import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkCollection
import com.unchunks.echomark.domain.bookmark.model.BookmarkListDisplay
import com.unchunks.echomark.domain.export.ExportFormat
import com.unchunks.echomark.domain.model.CategoryEntry
import com.unchunks.echomark.domain.model.CategoryKind
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.ui.selection.BulkActionCallbacks
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 一覧の複数選択の画面(長押し・メニューの「選択」で入る、タップで選ぶ、件数のバー、操作バー、戻る)のテスト。
 * これまで長押しで出していた操作メニューの操作(お気に入り・既読・アーカイブ・共有・削除)が、選択モードの操作バーから行えることも確かめる。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class BookmarkListSelectionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val bookmarks = listOf(
        PreviewSamples.urlBookmark.copy(id = 1, title = "一件目のタイトル", isFavorite = false),
        PreviewSamples.textBookmark.copy(id = 2, title = "二件目のタイトル", isFavorite = false),
        PreviewSamples.urlBookmark.copy(id = 3, title = "三件目のタイトル", isFavorite = false)
    )

    private class Calls {
        val entered = mutableListOf<Long?>()
        val toggled = mutableListOf<Long>()
        val opened = mutableListOf<Long>()
        val log = mutableListOf<String>()
        var exited = 0
        var selectedAll = 0
        var cleared = 0
    }

    private fun setContent(
        state: androidx.compose.runtime.MutableState<BookmarkListUiState>,
        calls: Calls,
        bulk: BulkActionCallbacks = BulkActionCallbacks()
    ) {
        composeRule.setContent {
            EchoMarkTheme {
                BookmarkListContent(
                    uiState = state.value,
                    snackbarHostState = SnackbarHostState(),
                    callbacks = BookmarkListCallbacks(
                        onOpenBookmark = { calls.opened += it },
                        onEnterSelection = { calls.entered += it },
                        onToggleSelection = { calls.toggled += it },
                        onSelectAll = { calls.selectedAll++ },
                        onClearSelection = { calls.cleared++ },
                        onExitSelection = { calls.exited++ },
                        bulk = bulk
                    ),
                    nowMillis = PreviewSamples.NOW
                )
            }
        }
    }

    private fun normal(display: BookmarkListDisplay = BookmarkListDisplay.CARD) =
        BookmarkListUiState(bookmarks = bookmarks, display = display, totalCount = bookmarks.size)

    private fun selecting(vararg ids: Long, display: BookmarkListDisplay = BookmarkListDisplay.CARD) =
        BookmarkListUiState(bookmarks = bookmarks, display = display, totalCount = bookmarks.size, isSelecting = true, selectedIds = ids.toSet())

    // ---- 入る ----

    @Test
    fun カードの長押しで選択を始める_どの見せ方でも() {
        BookmarkListDisplay.entries.forEach { display ->
            val calls = Calls()
            val state = mutableStateOf(normal(display))
            setContent(state, calls)

            composeRule.onNode(hasClickAction() and hasText("二件目のタイトル", substring = true)).performTouchInput { longClick() }

            assertEquals(display.name, listOf<Long?>(2L), calls.entered)
            assertTrue(calls.opened.isEmpty())
            composeRule.activityRule.scenario.recreate()
        }
    }

    @Test
    fun メニューの選択でも選択モードに入れる() {
        val calls = Calls()
        setContent(mutableStateOf(normal()), calls)

        composeRule.onNodeWithContentDescription("その他のメニュー").performClick()
        composeRule.onNodeWithText("選択").performClick()

        // 何も選ばない状態で入る
        assertEquals(listOf<Long?>(null), calls.entered)
    }

    @Test
    fun ブックマークが無いときはメニューの選択を使えない() {
        setContent(mutableStateOf(BookmarkListUiState(totalCount = 0)), Calls())

        composeRule.onNodeWithContentDescription("その他のメニュー").performClick()

        composeRule.onNodeWithText("選択").assertIsNotEnabled()
    }

    // ---- 選択モードの間 ----

    @Test
    fun 選択モードの間はタップで選びを切り替え_開かない() {
        val calls = Calls()
        setContent(mutableStateOf(selecting(1)), calls)

        composeRule.onNode(hasClickAction() and hasText("二件目のタイトル", substring = true)).performClick()

        assertEquals(listOf(2L), calls.toggled)
        assertTrue(calls.opened.isEmpty())
    }

    @Test
    fun 選択モードの間は長押しも選びを切り替える() {
        val calls = Calls()
        setContent(mutableStateOf(selecting(1)), calls)

        composeRule.onNode(hasClickAction() and hasText("三件目のタイトル", substring = true)).performTouchInput { longClick() }

        assertEquals(listOf(3L), calls.toggled)
        assertTrue(calls.entered.isEmpty())
    }

    @Test
    fun 選んでいるカードはTalkBackで選択中と読み_そうでないカードは選択していませんと読む() {
        setContent(mutableStateOf(selecting(1)), Calls())

        val states = bookmarks.map { bookmark ->
            composeRule.onNode(hasClickAction() and hasText(bookmark.title, substring = true)).fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.StateDescription)
        }

        assertEquals(listOf("選択中", "選択していません", "選択していません"), states)
    }

    @Test
    fun 選択モードの間は通常のトップバーと追加ボタンは出ず_件数と選びの操作が出る() {
        val calls = Calls()
        setContent(mutableStateOf(selecting(1, 3)), calls)

        composeRule.onNodeWithText("2件を選択中").assertIsDisplayed()
        composeRule.onNodeWithText("追加").assertIsNotDisplayedOrMissing()
        composeRule.onNodeWithContentDescription("検索").assertIsNotDisplayedOrMissing()

        composeRule.onNodeWithContentDescription("すべて選択").performClick()
        composeRule.onNodeWithContentDescription("選択を解除").performClick()
        composeRule.onNodeWithContentDescription("選択をやめる").performClick()

        assertEquals(1, calls.selectedAll)
        assertEquals(1, calls.cleared)
        assertEquals(1, calls.exited)
    }

    @Test
    fun すべて選んでいるときはすべて選択を押せず_何も選んでいなければ解除と操作を押せない() {
        val all = mutableStateOf(selecting(1, 2, 3))
        setContent(all, Calls())

        composeRule.onNodeWithContentDescription("すべて選択").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("選択を解除").assertIsEnabled()

        composeRule.runOnIdle { all.value = selecting() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("項目を選んでください").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("すべて選択").assertIsEnabled()
        composeRule.onNodeWithContentDescription("選択を解除").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("アーカイブ").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("削除").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("その他の操作").assertIsNotEnabled()
    }

    @Test
    fun 戻るでまず選択をやめる() {
        val calls = Calls()
        setContent(mutableStateOf(selecting(1)), calls)

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        assertEquals(1, calls.exited)
    }

    @Test
    fun 選択モードの間に検索を閉じる戻るは効かない_選択をやめたあとの戻るが検索を閉じる() {
        val closed = mutableListOf<Boolean>()
        val calls = Calls()
        val state = mutableStateOf(selecting(1).copy(isSearchActive = true, searchQuery = "タイトル"))
        composeRule.setContent {
            EchoMarkTheme {
                BookmarkListContent(
                    uiState = state.value,
                    snackbarHostState = SnackbarHostState(),
                    callbacks = BookmarkListCallbacks(
                        onSearchActiveChange = { closed += it },
                        onExitSelection = { calls.exited++ }
                    ),
                    nowMillis = PreviewSamples.NOW
                )
            }
        }

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(1, calls.exited)
        assertTrue(closed.isEmpty())

        // 選択をやめたあと(検索結果を出したまま)の戻るは、検索を閉じる
        composeRule.runOnIdle { state.value = state.value.copy(isSelecting = false, selectedIds = emptySet()) }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(listOf(false), closed)
    }

    // ---- 操作バー(これまで長押しのメニューで行っていた操作) ----

    @Test
    fun 操作バーからアーカイブ_お気に入り_既読_削除ができ_選んだものがすべてそうなら戻す操作になる() {
        val calls = Calls()
        val state = mutableStateOf(selecting(1, 2))
        setContent(
            state, calls,
            BulkActionCallbacks(
                onSetArchived = { calls.log += "archive:$it" },
                onSetFavorite = { calls.log += "favorite:$it" },
                onSetRead = { calls.log += "read:$it" },
                onDelete = { calls.log += "delete" }
            )
        )

        composeRule.onNodeWithContentDescription("アーカイブ").performClick()
        composeRule.onNodeWithContentDescription("お気に入りに追加").performClick()
        composeRule.onNodeWithContentDescription("既読にする").performClick()
        composeRule.onNodeWithContentDescription("削除").performClick()
        assertEquals(listOf("archive:true", "favorite:true", "read:true", "delete"), calls.log)

        // 選んだものがすべてアーカイブ済み・お気に入り・既読なら、元に戻す操作になる
        calls.log.clear()
        composeRule.runOnIdle {
            state.value = state.value.copy(
                bookmarks = bookmarks.map { it.copy(isArchived = true, isFavorite = true, readAt = 1L) }
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("アーカイブから戻す").performClick()
        composeRule.onNodeWithContentDescription("お気に入りから外す").performClick()
        composeRule.onNodeWithContentDescription("未読に戻す").performClick()
        assertEquals(listOf("archive:false", "favorite:false", "read:false"), calls.log)
    }

    @Test
    fun 一部だけが済んでいるときは済んでいない方に合わせる操作になる() {
        val calls = Calls()
        val mixed = bookmarks.mapIndexed { index, bookmark -> if (index == 0) bookmark.copy(isFavorite = true) else bookmark }
        setContent(
            mutableStateOf(selecting(1, 2).copy(bookmarks = mixed)), calls,
            BulkActionCallbacks(onSetFavorite = { calls.log += "favorite:$it" })
        )

        composeRule.onNodeWithContentDescription("お気に入りに追加").performClick()

        assertEquals(listOf("favorite:true"), calls.log)
    }

    @Test
    fun その他のメニューに残りの操作が並び_1件だけ選んでいるときは共有も出る() {
        val calls = Calls()
        val shared = mutableListOf<Bookmark>()
        setContent(mutableStateOf(selecting(2)), calls, BulkActionCallbacks(onShare = { shared += it }))

        composeRule.onNodeWithContentDescription("その他の操作").performClick()
        listOf("共有", "タグを付ける", "タグを外す", "カテゴリを設定", "コレクションに追加", "AI で再処理", "ファイルに書き出す").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
        // 一覧では、コレクションから外す操作は出さない(コレクションの画面だけ)
        composeRule.onNodeWithText("コレクションから外す").assertIsNotDisplayedOrMissing()
        composeRule.onNodeWithText("共有").performClick()

        assertEquals(listOf(2L), shared.map { it.id })
    }

    @Test
    fun 複数選んでいるときは共有を出さない() {
        setContent(mutableStateOf(selecting(1, 2)), Calls())

        composeRule.onNodeWithContentDescription("その他の操作").performClick()

        composeRule.onNodeWithText("共有").assertIsNotDisplayedOrMissing()
        composeRule.onNodeWithText("タグを付ける").assertIsDisplayed()
    }

    @Test
    fun タグを付ける_外す() {
        val calls = Calls()
        val tagged = bookmarks.map { if (it.id == 1L) it.copy(tags = listOf("Android", "Kotlin")) else if (it.id == 2L) it.copy(tags = listOf("Kotlin")) else it }
        setContent(
            mutableStateOf(selecting(1, 2).copy(bookmarks = tagged, allTags = listOf(com.unchunks.echomark.domain.model.Tag(1, "Android"), com.unchunks.echomark.domain.model.Tag(2, "Kotlin")))),
            calls,
            BulkActionCallbacks(onAddTag = { calls.log += "add:$it" }, onRemoveTag = { calls.log += "remove:$it" })
        )

        composeRule.onNodeWithContentDescription("その他の操作").performClick()
        composeRule.onNodeWithText("タグを付ける").performClick()
        composeRule.onNodeWithText("2件にタグを付ける").assertIsDisplayed()
        // 付けるボタンは名前を入れるまで押せない。候補を押すと入力に入る
        composeRule.onNodeWithText("付ける").assertIsNotEnabled()
        composeRule.onNodeWithText("Android").performClick()
        composeRule.onNodeWithText("付ける").assertIsEnabled().performClick()

        composeRule.onNodeWithContentDescription("その他の操作").performClick()
        composeRule.onNodeWithText("タグを外す").performClick()
        // 選んだものに付いているタグが、多く付いているものから並ぶ
        composeRule.onNodeWithText("#Kotlin").assertIsDisplayed()
        composeRule.onNodeWithText("#Kotlin").performClick()

        assertEquals(listOf("add:Android", "remove:Kotlin"), calls.log)
    }

    @Test
    fun カテゴリを選ぶ_コレクションに追加する_再処理は確認してから行う() {
        val calls = Calls()
        val collection = BookmarkCollection(7, "旅行の計画", null, 2, 1L)
        setContent(
            mutableStateOf(
                selecting(1, 2).copy(
                    collections = listOf(collection),
                    categoryChoices = listOf(CategoryEntry("ニュース", "時事の記事", CategoryKind.BUILT_IN, 3))
                )
            ),
            calls,
            BulkActionCallbacks(
                onSetCategory = { calls.log += "category:$it" },
                onAddToCollection = { calls.log += "collection:${it.name}" },
                onReprocess = { calls.log += "reprocess" }
            )
        )

        composeRule.onNodeWithContentDescription("その他の操作").performClick()
        composeRule.onNodeWithText("カテゴリを設定").performClick()
        composeRule.onNodeWithText("ニュース").performClick()

        composeRule.onNodeWithContentDescription("その他の操作").performClick()
        composeRule.onNodeWithText("コレクションに追加").performClick()
        composeRule.onNodeWithText("旅行の計画").performClick()

        composeRule.onNodeWithContentDescription("その他の操作").performClick()
        composeRule.onNodeWithText("AI で再処理").performClick()
        composeRule.onNodeWithText("2件を AI で再処理しますか?").assertIsDisplayed()
        // 確認のあと(やめるときは何もしない)
        composeRule.onNodeWithText("キャンセル").performClick()
        assertEquals(listOf("category:ニュース", "collection:旅行の計画"), calls.log)

        composeRule.onNodeWithContentDescription("その他の操作").performClick()
        composeRule.onNodeWithText("AI で再処理").performClick()
        composeRule.onNodeWithText("再処理する").performClick()
        assertEquals(listOf("category:ニュース", "collection:旅行の計画", "reprocess"), calls.log)
    }

    @Test
    fun 書き出しは形式を選ぶダイアログが出る() {
        val exported = mutableListOf<Pair<ExportFormat, String>>()
        setContent(mutableStateOf(selecting(1)), Calls(), BulkActionCallbacks(onExport = { format, uri -> exported += format to uri }))

        composeRule.onNodeWithContentDescription("その他の操作").performClick()
        composeRule.onNodeWithText("ファイルに書き出す").performClick()

        composeRule.onNodeWithText(ExportFormat.MARKDOWN.label).assertIsDisplayed()
        composeRule.onNodeWithText(ExportFormat.BROWSER_HTML.label).assertIsDisplayed()
        // 保存先を選ぶ画面は端末の機能なので、ここでは形式が出るところまで
        assertTrue(exported.isEmpty())
    }

    @Test
    fun 検索結果の中でも選択モードの表示になる() {
        val calls = Calls()
        setContent(
            mutableStateOf(selecting(1).copy(isSearchActive = true, searchQuery = "タイトル", searchTerms = listOf("タイトル"))),
            calls
        )

        composeRule.onNodeWithText("1件を選択中").assertIsDisplayed()
        composeRule.onNode(hasClickAction() and hasText("三件目のタイトル", substring = true)).performClick()
        assertEquals(listOf(3L), calls.toggled)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsNotDisplayedOrMissing() {
        if (runCatching { fetchSemanticsNode() }.isSuccess) assertIsNotDisplayed()
    }
}
