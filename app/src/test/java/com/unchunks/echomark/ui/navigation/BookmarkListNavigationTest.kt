package com.unchunks.echomark.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.model.TagWithCount
import com.unchunks.echomark.testing.FakeAttachmentRepository
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.FakeTagRepository
import com.unchunks.echomark.testing.testBookmark
import com.unchunks.echomark.ui.bookmark.BookmarkViewModel
import com.unchunks.echomark.ui.bookmark.ListLaunchAction
import com.unchunks.echomark.ui.common.RecentlyDeletedBookmarks
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 一覧への依頼(タグ管理からの絞り込み・ショートカットからの追加/検索)が、NavHost を通して実際に ViewModel に届くか。
 *
 * ViewModel の単体テストは関数を直接呼ぶため、「NavBackStackEntry の SavedStateHandle に入れた値が ViewModel に届かない」
 * という配線の誤りを検出できない。ここでは本物の NavHost・バックスタック・エントリに紐づく ViewModel で確かめる
 * (Hilt は Robolectric で使えないため、ViewModel は hiltViewModel() と同じくエントリをストアにして viewModel {} で作る)。
 */
@RunWith(AndroidJUnit4::class)
class BookmarkListNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val repository = FakeBookmarkRepository()
    private val tagRepository = FakeTagRepository()
    private val recentlyDeleted = RecentlyDeletedBookmarks()

    private lateinit var navController: NavHostController
    private var listViewModel: BookmarkViewModel? = null

    /** 本番と同じ一覧画面([BookmarkListDestination])と、仮のサブ画面を並べた NavHost を表示する */
    private fun setContent() {
        composeRule.setContent {
            val controller = rememberNavController()
            navController = controller
            EchoMarkTheme {
                NavHost(navController = controller, startDestination = BOOKMARKS_ROUTE) {
                    composable(BOOKMARKS_ROUTE) { entry ->
                        val viewModel = viewModel { BookmarkViewModel(repository, tagRepository, recentlyDeleted, FakeAttachmentRepository()) }
                        listViewModel = viewModel
                        BookmarkListDestination(
                            entry = entry,
                            onOpenBookmark = { controller.navigate(Routes.bookmarkDetail(it)) },
                            onOpenTagManagement = { controller.navigate(Routes.TAGS) },
                            viewModel = viewModel
                        )
                    }
                    composable(Routes.TAGS) { Text("タグ管理(仮)") }
                    composable(Routes.BOOKMARK_DETAIL) { Text("詳細(仮)") }
                    composable("chat") { Text("会話一覧(仮)") }
                    composable(CHAT_NEW_ROUTE) { Text("新しいチャット(仮)") }
                    composable("settings") { Text("設定(仮)") }
                    composable(Routes.AI_SETTINGS) { Text("AI 設定(仮)") }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun onUi(block: () -> Unit) {
        composeRule.runOnIdle(block)
        composeRule.waitForIdle()
    }

    private val currentRoute: String? get() = navController.currentBackStackEntry?.destination?.route

    @Test
    fun タグ管理でタグを選ぶと一覧に戻ってそのタグで絞り込む() {
        repository.bookmarks.value = listOf(testBookmark(1).copy(tags = listOf("10")), testBookmark(2))
        tagRepository.tags.value = listOf(TagWithCount(10, "kotlin", 1))
        setContent()
        val viewModel = assertNotNull(listViewModel).let { listViewModel!! }
        onUi { viewModel.onSearchActiveChange(true) }
        onUi { navController.navigate(Routes.TAGS) }
        assertEquals(Routes.TAGS, currentRoute)

        onUi { navController.returnToBookmarkListWithTag(10L) }

        assertEquals(BOOKMARKS_ROUTE, currentRoute)
        // 一覧に戻っても同じ ViewModel(エントリに紐づく)で、絞り込みが届いている
        assertSame(viewModel, listViewModel)
        val state = viewModel.uiState.value
        assertEquals(10L, state.selectedTagId)
        assertFalse("検索モードは閉じる", state.isSearchActive)
        assertEquals(listOf(1L), state.bookmarks.map { it.id })
        // 依頼は取り出したら消える(戻ってきたときに繰り返さない)
        assertNull(navController.currentBackStackEntry!!.savedStateHandle.get<Long>(BookmarkListRequests.KEY_SELECT_TAG_ID))
    }

    @Test
    fun ショートカットの検索で一覧が検索モードになる() {
        setContent()

        onUi { navController.openLaunchTarget(LaunchTarget.SEARCH) }

        assertTrue(listViewModel!!.uiState.value.isSearchActive)
        composeRule.onNodeWithContentDescription("検索を閉じる").assertExists()
    }

    @Test
    fun ショートカットのURLを追加で追加シートが開く() {
        setContent()

        onUi { navController.openLaunchTarget(LaunchTarget.ADD_BOOKMARK) }

        composeRule.onNodeWithText("ブックマークを追加").assertExists()
        assertNull(navController.currentBackStackEntry!!.savedStateHandle.get<String>(BookmarkListRequests.KEY_LAUNCH_ACTION))
    }

    @Test
    fun サブ画面を開いていてもショートカットの検索で一覧まで戻って検索モードになる() {
        setContent()
        onUi { navController.navigate(Routes.bookmarkDetail(1)) }
        assertEquals(Routes.BOOKMARK_DETAIL, currentRoute)

        onUi { navController.openLaunchTarget(LaunchTarget.SEARCH) }

        assertEquals(BOOKMARKS_ROUTE, currentRoute)
        assertTrue(listViewModel!!.uiState.value.isSearchActive)
    }

    @Test
    fun ショートカットの新しいチャットとAI設定はタブを経由して開く() {
        setContent()

        onUi { navController.openLaunchTarget(LaunchTarget.NEW_CHAT) }
        assertEquals(CHAT_NEW_ROUTE, currentRoute)
        assertEquals("chat", navController.previousBackStackEntry?.destination?.route)

        onUi { navController.openLaunchTarget(LaunchTarget.AI_SETTINGS) }
        assertEquals(Routes.AI_SETTINGS, currentRoute)
        assertEquals("settings", navController.previousBackStackEntry?.destination?.route)
    }

    @Test
    fun 未知の起動操作は無視して消す() {
        setContent()

        onUi {
            navController.getBackStackEntry(BOOKMARKS_ROUTE).savedStateHandle[BookmarkListRequests.KEY_LAUNCH_ACTION] = "UNKNOWN"
        }

        assertFalse(listViewModel!!.uiState.value.isSearchActive)
        assertNull(navController.currentBackStackEntry!!.savedStateHandle.get<String>(BookmarkListRequests.KEY_LAUNCH_ACTION))
    }

    /**
     * 前提の確認: エントリの SavedStateHandle は、エントリをストアにして作った ViewModel の SavedStateHandle とは別物。
     * (以前はエントリ側に入れた値を ViewModel 側で読もうとしていたため、絞り込みやショートカットが届かなかった)
     */
    @Test
    fun エントリのSavedStateHandleはViewModelのSavedStateHandleと別物() {
        var viewModelHandle: SavedStateHandle? = null
        composeRule.setContent {
            val controller = rememberNavController()
            navController = controller
            NavHost(navController = controller, startDestination = BOOKMARKS_ROUTE) {
                composable(BOOKMARKS_ROUTE) {
                    viewModelHandle = viewModel { HandleHolder(createSavedStateHandle()) }.handle
                }
            }
        }
        composeRule.waitForIdle()

        onUi { navController.getBackStackEntry(BOOKMARKS_ROUTE).savedStateHandle["key"] = 1L }

        assertNull(viewModelHandle!!.get<Long>("key"))
    }

    private class HandleHolder(val handle: SavedStateHandle) : ViewModel()
}
