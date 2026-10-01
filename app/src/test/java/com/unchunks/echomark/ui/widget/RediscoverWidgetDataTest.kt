package com.unchunks.echomark.ui.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.rediscover.RediscoverSelector
import com.unchunks.echomark.testing.testBookmark
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** ウィジェットに出す内容の選び方・変換。ドメイン名の取り出しに android.net.Uri を使うため Robolectric で動かす。 */
@RunWith(AndroidJUnit4::class)
class RediscoverWidgetDataTest {

    private val now = TimeUnit.DAYS.toMillis(1000)
    private val day = TimeUnit.DAYS.toMillis(1)

    @Test
    fun しばらく開いていないものだけを_アーカイブを除いて最大3件選ぶ() {
        val bookmarks = listOf(
            testBookmark(1, lastAccessedAt = now - 20 * day),
            testBookmark(2, lastAccessedAt = now - 1 * day), // 最近開いた
            testBookmark(3, lastAccessedAt = now - 30 * day).copy(isArchived = true),
            testBookmark(4, lastAccessedAt = now - 40 * day),
            testBookmark(5, lastAccessedAt = now - RediscoverSelector.IN_APP_STALE_DAYS * day)
        )

        val picked = RediscoverWidgetData.select(bookmarks, now)

        // 古い順(候補が3件以下なら日替わりの回転はしない)
        assertEquals(listOf(4L, 1L, 5L), picked.map { it.id })
    }

    @Test
    fun 一覧の今日の再発見と同じく日替わりで入れ替わる() {
        val bookmarks = (1L..7L).map { testBookmark(it, lastAccessedAt = now - (30 + it) * day) }

        val today = RediscoverWidgetData.select(bookmarks, now)
        val expected = RediscoverSelector.pickDaily(
            bookmarks.sortedBy { it.lastAccessedAt }, now = now, dayIndex = TimeUnit.MILLISECONDS.toDays(now)
        )
        assertEquals(expected, today)
        val tomorrow = RediscoverWidgetData.select(bookmarks, now + day)
        assertTrue(today != tomorrow)
    }

    @Test
    fun 表示用の行はタイトルと出どころ() {
        val url = testBookmark(1, title = "記事").copy(
            type = BookmarkType.URL, contentUri = "https://www.example.com/a"
        )
        val site = url.copy(id = 2, siteName = "Example Blog")
        val memo = testBookmark(3, title = "メモの題")

        assertEquals(
            listOf(
                RediscoverWidgetItem(1, "記事", "example.com"),
                RediscoverWidgetItem(2, "記事", "Example Blog"),
                RediscoverWidgetItem(3, "メモの題", "メモ")
            ),
            RediscoverWidgetData.toItems(listOf(url, site, memo))
        )
    }

    @Test
    fun 件数は高さで_ヘッダーの文字は幅で決める() {
        assertEquals(
            RediscoverWidgetLayout(maxItems = 1, showTitle = false, showSource = false),
            RediscoverWidgetData.layoutFor(RediscoverWidgetData.SMALL_SIZE)
        )
        assertEquals(
            RediscoverWidgetLayout(maxItems = 2, showTitle = false, showSource = true),
            RediscoverWidgetData.layoutFor(RediscoverWidgetData.MEDIUM_SIZE)
        )
        assertEquals(
            RediscoverWidgetLayout(maxItems = 3, showTitle = true, showSource = true),
            RediscoverWidgetData.layoutFor(DpSize(260.dp, 260.dp))
        )
        assertEquals(1, RediscoverWidgetData.layoutFor(DpSize(300.dp, 150.dp)).maxItems)
        // Responsive に渡す大きさは、どれも出し方が決まる(高さ3段階 × 幅2段階)
        assertEquals(6, RediscoverWidgetData.RESPONSIVE_SIZES.map { RediscoverWidgetData.layoutFor(it) }.toSet().size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun 表示が変わるときだけ更新を流す() = runTest {
        val bookmarks = MutableStateFlow(listOf(testBookmark(1, lastAccessedAt = now - 30 * day)))
        val dynamicColor = MutableStateFlow(false)
        val changes = mutableListOf<Pair<List<RediscoverWidgetItem>, Boolean>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            RediscoverWidgetUpdater.widgetContentChanges(bookmarks, dynamicColor, clock = { now }, debounceMs = 100)
                .toList(changes)
        }
        advanceTimeBy(200); runCurrent()
        assertEquals(1, changes.size)

        // AI の要約が付いただけ(表示に関係ない)なら流さない
        bookmarks.value = listOf(bookmarks.value.single().copy(summary = "要約"))
        advanceTimeBy(200); runCurrent()
        assertEquals(1, changes.size)

        // 開いた(最終アクセスが新しくなった)ら再発見から外れる
        bookmarks.value = listOf(bookmarks.value.single().copy(lastAccessedAt = now))
        advanceTimeBy(200); runCurrent()
        assertEquals(2, changes.size)
        assertTrue(changes.last().first.isEmpty())

        // 配色の設定が変わったら描き直す
        dynamicColor.value = true
        advanceTimeBy(200); runCurrent()
        assertEquals(3, changes.size)

        // 短い間の連続した変更は1回にまとめる
        bookmarks.value = listOf(testBookmark(5, lastAccessedAt = now - 30 * day))
        bookmarks.value = listOf(testBookmark(6, lastAccessedAt = now - 30 * day))
        advanceTimeBy(200); runCurrent()
        assertEquals(4, changes.size)
        assertEquals(listOf(6L), changes.last().first.map { it.bookmarkId })
    }
}
