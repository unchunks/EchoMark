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

/** サブ画面の「戻る」を素早く2回押しても、開始画面より前まで戻って何も表示されなくならないこと。 */
@RunWith(AndroidJUnit4::class)
class NavigationBackTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var navController: NavHostController
    private var onBack: () -> Unit = {}
    private var onSelect: (Long) -> Unit = {}
    private val selected = mutableListOf<Long>()

    private fun setContent() {
        composeRule.setContent {
            val controller = rememberNavController()
            navController = controller
            NavHost(navController = controller, startDestination = BOOKMARKS_ROUTE) {
                composable(BOOKMARKS_ROUTE) { Text("一覧") }
                composable(Routes.BOOKMARK_DETAIL) {
                    // 本番の NavHost と同じ「戻る」の作り方
                    onBack = controller.popBackAction()
                    onSelect = dropUnlessResumedWith { id: Long ->
                        selected += id
                        controller.popBackStack()
                    }
                    Text("詳細")
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { navController.navigate(Routes.bookmarkDetail(1)) }
        composeRule.waitForIdle()
    }

    private val routes: List<String>
        get() = navController.currentBackStack.value.mapNotNull { it.destination.route }

    @Test
    fun 戻るを2回続けて押しても開始画面に留まる() {
        setContent()
        assertEquals(Routes.BOOKMARK_DETAIL, navController.currentDestination?.route)

        composeRule.runOnIdle {
            onBack()
            onBack()
        }
        composeRule.waitForIdle()

        assertEquals(BOOKMARKS_ROUTE, navController.currentDestination?.route)
        assertEquals(listOf(BOOKMARKS_ROUTE), routes)
    }

    @Test
    fun 引数つきの操作も連打の2回目は無視する() {
        setContent()

        composeRule.runOnIdle {
            onSelect(1L)
            onSelect(2L)
        }
        composeRule.waitForIdle()

        assertEquals(listOf(1L), selected)
        assertEquals(BOOKMARKS_ROUTE, navController.currentDestination?.route)
    }
}
