package com.unchunks.echomark.ui.places

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.place.PlaceGroup
import com.unchunks.echomark.domain.place.Places
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 場所の画面の地図の部分の切り替え(分布図とタイルの地図)。タイルの地図は Fake に差し替えて、
 * 「いつタイルの地図を作る(= 通信を始める)か」「失敗・オフラインでどう戻るか」を確かめる。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h900dp-xhdpi")
class PlacesMapAreaTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val groups: List<PlaceGroup> = Places.group(
        listOf(
            Bookmark(id = 1, type = BookmarkType.URL, title = "t", createdAt = 0, lastAccessedAt = 0, latitude = 35.0, longitude = 139.0, placeName = "A")
        )
    )

    private class FakeTileMap {
        var created = 0
        var onFailed: ((String) -> Unit)? = null
        val slot: TileMapSlot = { _, _, _, failed, modifier ->
            created++
            onFailed = failed
            Box(modifier.fillMaxSize()) { Text("TILE-MAP") }
        }
    }

    private val fake = FakeTileMap()

    private val base get() = PlacesUiState(isLoading = false, groups = groups, locatedCount = 1)

    private fun show(state: PlacesUiState, online: Boolean = true, onSwitch: (Boolean) -> Unit = {}) {
        composeRule.setContent {
            EchoMarkTheme {
                PlacesContent(
                    uiState = state, onBack = {}, onOpenBookmark = {}, onSelect = {}, onDetect = {}, onOpenInMap = { _, _ -> },
                    onMapBackgroundChange = onSwitch, isOnline = online, tileMap = fake.slot
                )
            }
        }
    }

    @Test
    fun スイッチがオフなら_タイルの地図は作らず分布図だけ() {
        show(base)
        composeRule.onNodeWithText("TILE-MAP").assertDoesNotExist()
        assertEquals(0, fake.created)
        composeRule.onNodeWithText("地図の背景を表示").assertExists()
    }

    @Test
    fun スイッチがオンでオンラインなら_タイルの地図を出す() {
        show(base.copy(mapBackgroundEnabled = true))
        composeRule.onNodeWithText("TILE-MAP").assertIsDisplayed()
        assertEquals(1, fake.created)
    }

    @Test
    fun オフラインなら_タイルの地図は作らず_分布図と理由を出す() {
        show(base.copy(mapBackgroundEnabled = true), online = false)
        composeRule.onNodeWithText("オフラインのため、地図の背景は出せません。分布図を表示しています。").assertIsDisplayed()
        composeRule.onNodeWithText("TILE-MAP").assertDoesNotExist()
        assertEquals(0, fake.created)
    }

    @Test
    fun 読み込みに失敗したら_分布図に戻り_もう一度試すで作り直す() {
        show(base.copy(mapBackgroundEnabled = true))
        assertEquals(1, fake.created)

        composeRule.runOnIdle { fake.onFailed!!.invoke("style") }
        composeRule.onNodeWithText("地図の背景を読み込めませんでした。分布図を表示しています。").assertIsDisplayed()
        composeRule.onNodeWithText("TILE-MAP").assertDoesNotExist()

        composeRule.onNodeWithText("もう一度試す").performClick()
        composeRule.onNodeWithText("TILE-MAP").assertIsDisplayed()
        assertEquals(2, fake.created)
    }

    @Test
    fun ネットワークが戻ったら_自動でもう一度試す() {
        val online = mutableStateOf(false)
        composeRule.setContent {
            EchoMarkTheme {
                PlacesContent(
                    uiState = base.copy(mapBackgroundEnabled = true), onBack = {}, onOpenBookmark = {}, onSelect = {}, onDetect = {},
                    onOpenInMap = { _, _ -> }, isOnline = online.value, tileMap = fake.slot
                )
            }
        }
        assertEquals(0, fake.created)
        online.value = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("TILE-MAP").assertIsDisplayed()
        assertEquals(1, fake.created)
    }

    @Test
    fun スイッチを押すと変更が伝わる() {
        val changes = mutableListOf<Boolean>()
        show(base, onSwitch = { changes += it })
        // 行の文字を押すのではなく、スイッチそのもの(切り替えのロールを持つ要素)を押す
        composeRule.onNode(androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.isToggleable()).performClick()
        assertEquals(listOf(true), changes)
    }
}
