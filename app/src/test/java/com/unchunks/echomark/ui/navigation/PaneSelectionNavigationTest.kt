package com.unchunks.echomark.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.ui.chat.ChatViewModel
import com.unchunks.echomark.ui.detail.BookmarkDetailViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2 画面表示になったとき、一覧の上にルートで開いていた詳細・会話を右側のペインへ移すこと([moveDetailRouteIntoPane])。
 * 一覧から開いたものだけを移し、チャットの引用など別の画面から開いた詳細は戻る先を変えないよう、そのままにする。
 */
@RunWith(AndroidJUnit4::class)
class PaneSelectionNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var navController: NavHostController

    private fun setContent() {
        composeRule.setContent {
            val controller = rememberNavController()
            navController = controller
            // 本番の NavHost と同じルート構成(画面の中身だけ置き換える)
            NavHost(navController = controller, startDestination = BOOKMARKS_ROUTE) {
                composable(BOOKMARKS_ROUTE) { Text("一覧") }
                composable(CHAT_ROUTE) { Text("会話一覧") }
                composable(CHAT_NEW_ROUTE) { Text("新しいチャット") }
                composable(
                    route = CHAT_CONVERSATION_ROUTE,
                    arguments = listOf(navArgument(ChatViewModel.ARG_CONVERSATION_ID) { type = NavType.LongType })
                ) { Text("会話") }
                composable(
                    route = Routes.BOOKMARK_DETAIL,
                    arguments = listOf(navArgument(BookmarkDetailViewModel.ARG_BOOKMARK_ID) { type = NavType.LongType })
                ) { Text("詳細") }
            }
        }
        composeRule.waitForIdle()
    }

    private fun act(block: NavHostController.() -> Unit) {
        composeRule.runOnIdle { navController.block() }
        composeRule.waitForIdle()
    }

    private val routes: List<String>
        get() = navController.currentBackStack.value.mapNotNull { it.destination.route }

    @Test
    fun 一覧から開いた詳細は右側のペインへ移る() {
        setContent()
        act { navigate(Routes.bookmarkDetail(5)) }

        var moved = false
        act { moved = moveDetailRouteIntoPane() }

        assertTrue(moved)
        assertEquals(listOf(BOOKMARKS_ROUTE), routes)
        val handle = navController.getBackStackEntry(BOOKMARKS_ROUTE).savedStateHandle
        assertEquals(5L, handle.get<Long>(PaneSelection.KEY_BOOKMARK_ID))
    }

    @Test
    fun 会話一覧から開いた会話は右側のペインへ移る() {
        setContent()
        act { navigateToTopLevel(CHAT_ROUTE) }
        act { navigate("chat/7") }

        act { moveDetailRouteIntoPane() }

        assertEquals(listOf(BOOKMARKS_ROUTE, CHAT_ROUTE), routes)
        val handle = navController.getBackStackEntry(CHAT_ROUTE).savedStateHandle
        assertEquals(ChatPaneSession.conversation(7), handle.get<String>(PaneSelection.KEY_CHAT_SESSION))
        assertEquals(7L, handle.get<Long>(PaneSelection.KEY_CHAT_CONVERSATION_ID))
        assertEquals(7L, ChatPaneSession.args(ChatPaneSession.conversation(7)).getLong(ChatViewModel.ARG_CONVERSATION_ID))
    }

    @Test
    fun チャットの引用から開いた詳細と新しいチャットはそのまま() {
        setContent()
        act { navigateToTopLevel(CHAT_ROUTE) }
        act { navigate("chat/7") }
        act { navigate(Routes.bookmarkDetail(3)) }

        var moved = true
        act { moved = moveDetailRouteIntoPane() }
        assertFalse(moved)
        assertEquals(listOf(BOOKMARKS_ROUTE, CHAT_ROUTE, CHAT_CONVERSATION_ROUTE, Routes.BOOKMARK_DETAIL), routes)

        act { popBackStack(CHAT_ROUTE, inclusive = false) }
        act { navigate(CHAT_NEW_ROUTE) }
        act { moved = moveDetailRouteIntoPane() }
        assertFalse(moved)
        assertNull(navController.getBackStackEntry(CHAT_ROUTE).savedStateHandle.get<String>(PaneSelection.KEY_CHAT_SESSION))
    }
}
