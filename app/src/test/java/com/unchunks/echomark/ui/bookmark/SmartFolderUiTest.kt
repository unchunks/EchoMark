package com.unchunks.echomark.ui.bookmark

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.bookmark.model.BookmarkQuery
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.bookmark.model.ReadState
import com.unchunks.echomark.domain.bookmark.model.SmartFolder
import com.unchunks.echomark.domain.bookmark.model.SmartFolderResult
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** スマートフォルダ(#34)の行・保存のダイアログ・操作のシートのテスト。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class SmartFolderUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val unread = SmartFolder(1, "未読", BookmarkQuery(readState = ReadState.UNREAD), BookmarkSortOrder.OLDEST)
    private val reading = SmartFolder(2, "読んだもの", BookmarkQuery(readState = ReadState.READ))

    private fun setContent(
        state: BookmarkListUiState,
        callbacks: BookmarkListCallbacks
    ) {
        composeRule.setContent {
            EchoMarkTheme {
                BookmarkListContent(
                    uiState = state,
                    snackbarHostState = SnackbarHostState(),
                    callbacks = callbacks,
                    nowMillis = PreviewSamples.NOW
                )
            }
        }
    }

    private fun state(
        folders: List<SmartFolder> = listOf(unread, reading),
        activeId: Long? = null,
        toUpdate: SmartFolder? = null,
        narrowed: Boolean = false
    ) = BookmarkListUiState(
        bookmarks = PreviewSamples.all,
        smartFolders = folders,
        activeSmartFolderId = activeId,
        smartFolderToUpdate = toUpdate,
        readState = if (narrowed) ReadState.UNREAD else null,
        totalCount = 4
    )

    @Test
    fun フォルダを押すと開き_開いているフォルダは選択中と読み上げる() {
        val selected = mutableListOf<SmartFolder>()
        setContent(state(activeId = null), BookmarkListCallbacks(onSmartFolderSelect = { selected += it }))

        composeRule.onNodeWithContentDescription("スマートフォルダ「未読」").performClick()
        assertEquals(listOf(unread), selected)
        composeRule.onNodeWithText("読んだもの").assertIsDisplayed()
    }

    @Test
    fun 条件を付けているときだけこの条件を保存を出す() {
        val state = mutableStateOf(state(narrowed = false))
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
        composeRule.onNodeWithText("この条件を保存").assertDoesNotExist()

        composeRule.runOnIdle { state.value = state(narrowed = true) }
        composeRule.onNodeWithText("この条件を保存").assertIsDisplayed()

        // 同じ条件のフォルダが開いているときは、また保存する必要は無い
        composeRule.runOnIdle { state.value = state(narrowed = true, activeId = unread.id) }
        composeRule.onNodeWithText("この条件を保存").assertDoesNotExist()
    }

    // 注意: 名前を入れるダイアログ(文字入力欄を持つダイアログ)は、Robolectric では入力まわりの処理が終わらず Compose が
    // 落ち着かなくなるため、この画面のテストでは開かない。名前の決まりは SmartFolderRepositoryImplTest、保存・名前の変更・結果の
    // 扱いは BookmarkViewModelTest、結果に応じた知らせは下の errorMessage のテストで確かめる。

    @Test
    fun 開いているフォルダの操作のシートから削除を選ぶと_確かめてから削除する() {
        val deleted = mutableListOf<SmartFolder>()
        setContent(
            state(activeId = unread.id, narrowed = true),
            BookmarkListCallbacks(onDeleteSmartFolder = { deleted += it })
        )

        composeRule.onNodeWithContentDescription("「未読」の操作を開く").performClick()
        composeRule.onNodeWithText("名前を変える").assertIsDisplayed()
        composeRule.onNodeWithText("削除する").performClick()
        composeRule.onNodeWithText("削除しますか?").assertIsDisplayed()
        assertEquals(emptyList<SmartFolder>(), deleted)

        composeRule.onNodeWithText("削除").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(unread), deleted)
        composeRule.onNodeWithText("削除しますか?").assertDoesNotExist()
    }

    @Test
    fun 削除の確かめでキャンセルすれば削除しない() {
        val deleted = mutableListOf<SmartFolder>()
        setContent(
            state(activeId = unread.id, narrowed = true),
            BookmarkListCallbacks(onDeleteSmartFolder = { deleted += it })
        )

        composeRule.onNodeWithContentDescription("「未読」の操作を開く").performClick()
        composeRule.onNodeWithText("削除する").performClick()
        composeRule.onNodeWithText("キャンセル").performClick()
        composeRule.waitForIdle()

        assertEquals(emptyList<SmartFolder>(), deleted)
        composeRule.onNodeWithText("削除しますか?").assertDoesNotExist()
    }

    @Test
    fun 保存と名前の変更の結果に応じた知らせを出す() {
        assertEquals(null, SmartFolderResult.Saved(1).errorMessage())
        assertEquals("同じ名前のフォルダがあります", SmartFolderResult.NameTaken.errorMessage())
        assertEquals("名前は1〜30文字で入力してください", SmartFolderResult.InvalidName.errorMessage())
    }

    @Test
    fun 開いたフォルダの条件を変えたときは更新のチップを出す() {
        val updated = mutableListOf<SmartFolder>()
        val state = mutableStateOf(state(toUpdate = unread, narrowed = true))
        composeRule.setContent {
            EchoMarkTheme {
                BookmarkListContent(
                    uiState = state.value,
                    snackbarHostState = SnackbarHostState(),
                    callbacks = BookmarkListCallbacks(onUpdateSmartFolder = { updated += it }),
                    nowMillis = PreviewSamples.NOW
                )
            }
        }

        composeRule.onNodeWithContentDescription("今の条件でスマートフォルダ「未読」を上書き").performClick()
        assertEquals(listOf(unread), updated)
        // 新しいフォルダとしても保存できる(同じ条件のフォルダが無いので)
        composeRule.onNodeWithText("この条件を保存").assertIsDisplayed()
    }
}
