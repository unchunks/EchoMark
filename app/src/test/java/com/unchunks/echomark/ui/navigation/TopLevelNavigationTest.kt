package com.unchunks.echomark.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ボトムバーのタブ切り替え([navigateToTopLevel])。
 * タブを通らずに開いた画面(初回案内の「AI の設定へ進む」・アプリショートカット)から戻ったあとでも、
 * ブックマークタブを押すとブックマーク一覧が開くこと。
 */
@RunWith(AndroidJUnit4::class)
class TopLevelNavigationTest {

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
                composable(SETTINGS_ROUTE) { Text("設定") }
                composable(Routes.AI_SETTINGS) { Text("AI 設定") }
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
    fun 初回案内からAI設定へ進んで戻ったあと_ブックマークタブで一覧が開く() {
        setContent()
        act { openLaunchTarget(LaunchTarget.AI_SETTINGS) }
        act { popBackStack() }
        assertEquals(SETTINGS_ROUTE, navController.currentDestination?.route)

        act { navigateToTopLevel(BOOKMARKS_ROUTE) }
        assertEquals(listOf(BOOKMARKS_ROUTE), routes)

        // 2回目以降も一覧のまま
        act { navigateToTopLevel(SETTINGS_ROUTE) }
        act { navigateToTopLevel(BOOKMARKS_ROUTE) }
        assertEquals(listOf(BOOKMARKS_ROUTE), routes)
    }

    @Test
    fun ショートカットから新しいチャットを開いたあと_ブックマークタブで一覧が開く() {
        setContent()
        act { openLaunchTarget(LaunchTarget.NEW_CHAT) }
        act { navigateToTopLevel(BOOKMARKS_ROUTE) }
        assertEquals(listOf(BOOKMARKS_ROUTE), routes)
    }

    @Test
    fun タブを順に切り替えても毎回そのタブが開く() {
        setContent()
        listOf(SETTINGS_ROUTE, BOOKMARKS_ROUTE, CHAT_ROUTE, BOOKMARKS_ROUTE, SETTINGS_ROUTE, CHAT_ROUTE, BOOKMARKS_ROUTE)
            .forEach { route ->
                act { navigateToTopLevel(route) }
                assertEquals(route, navController.currentDestination?.route)
            }
    }

    @Test
    fun タブ内で開いていた画面は_タブに戻ると復元される() {
        setContent()
        act { navigateToTopLevel(CHAT_ROUTE) }
        act { navigate(CHAT_NEW_ROUTE) }
        act { navigateToTopLevel(BOOKMARKS_ROUTE) }
        assertEquals(listOf(BOOKMARKS_ROUTE), routes)

        act { navigateToTopLevel(CHAT_ROUTE) }
        assertEquals(listOf(BOOKMARKS_ROUTE, CHAT_ROUTE, CHAT_NEW_ROUTE), routes)
    }

    @Test
    fun ブックマークタブを表示中にもう一度押しても一覧のまま() {
        setContent()
        act { navigateToTopLevel(BOOKMARKS_ROUTE) }
        assertEquals(listOf(BOOKMARKS_ROUTE), routes)
    }

    private companion object {
        const val CHAT_ROUTE = "chat"
        const val SETTINGS_ROUTE = "settings"
    }
}
