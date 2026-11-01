package com.unchunks.echomark.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasAnyDescendant
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.MainActivity
import com.unchunks.echomark.R
import com.unchunks.echomark.ui.navigation.LaunchTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ウィジェットの中身(Glance)の構成を、glance-appwidget-testing で確認する(見た目までは確認できない)。
 * 文字列リソースと Intent を使うため Robolectric で動かす。
 */
@RunWith(AndroidJUnit4::class)
class RediscoverWidgetContentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val items = listOf(
        RediscoverWidgetItem(1, "Compose のパフォーマンス", "example.com"),
        RediscoverWidgetItem(2, "読書メモ", "メモ"),
        RediscoverWidgetItem(3, "Kotlin コルーチン入門", "kotlinlang.org")
    )

    @Test
    fun 大きいサイズでは3件とヘッダーのボタンを出す() = runGlanceAppWidgetUnitTest {
        setContext(context)
        val size = DpSize(260.dp, RediscoverWidgetData.LARGE_SIZE.height)
        setAppWidgetSize(size)
        provideComposable { RediscoverWidgetContent(items, RediscoverWidgetData.layoutFor(size)) }

        onNode(hasTextEqualTo(context.getString(R.string.widget_rediscover_title))).assertExists()
        items.forEach { onNode(hasTextEqualTo(it.title)).assertExists() }
        onNode(hasTextEqualTo("example.com")).assertExists()
        // ボタン(押せる部分)の中にアイコンがある。Intent の比較は extra を見ないので、起動先は別のテストで確認する
        onNode(
            hasStartActivityClickAction(launchTargetIntent(context, LaunchTarget.ADD_BOOKMARK))
                .and(hasAnyDescendant(hasContentDescriptionEqualTo(ADD_LABEL)))
        ).assertExists()
        onNode(
            hasStartActivityClickAction(launchTargetIntent(context, LaunchTarget.NEW_CHAT))
                .and(hasAnyDescendant(hasContentDescriptionEqualTo(CHAT_LABEL)))
        ).assertExists()
        onNode(hasText(EMPTY_MESSAGE)).assertDoesNotExist()
    }

    @Test
    fun 小さいサイズでは1件だけ出す() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(RediscoverWidgetData.SMALL_SIZE)
        provideComposable { RediscoverWidgetContent(items, RediscoverWidgetData.layoutFor(RediscoverWidgetData.SMALL_SIZE)) }

        // 行全体を押すと詳細を開く
        onNode(
            hasStartActivityClickAction(bookmarkDetailIntent(context, items[0].bookmarkId))
                .and(hasAnyDescendant(hasTextEqualTo(items[0].title)))
        ).assertExists()
        onNode(hasTextEqualTo(items[1].title)).assertDoesNotExist()
        onNode(hasTextEqualTo(items[2].title)).assertDoesNotExist()
        // 高さが足りないので、出どころとヘッダーの文字は出さない(ボタンは残す)
        onNode(hasTextEqualTo(items[0].source)).assertDoesNotExist()
        onNode(hasTextEqualTo(context.getString(R.string.widget_rediscover_title))).assertDoesNotExist()
        onNode(hasContentDescriptionEqualTo(ADD_LABEL)).assertExists()
    }

    @Test
    fun 空のときは案内を出す() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { RediscoverWidgetContent(emptyList(), RediscoverWidgetData.layoutFor(RediscoverWidgetData.LARGE_SIZE)) }

        onNode(hasTextEqualTo(EMPTY_MESSAGE)).assertExists()
        // 空でも追加・チャットのボタンは使える
        onNode(hasContentDescriptionEqualTo(ADD_LABEL)).assertExists()
    }

    @Test
    fun 詳細はディープリンクで_追加とチャットはショートカットと同じ起動先で開く() {
        val detail = bookmarkDetailIntent(context, 42)
        assertEquals("echomark://bookmark/42", detail.dataString)
        assertEquals(MainActivity::class.java.name, detail.component?.className)
        assertTrue(detail.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)

        val add = launchTargetIntent(context, LaunchTarget.ADD_BOOKMARK)
        assertEquals(LaunchTarget.ADD_BOOKMARK, LaunchTarget.fromIntent(add))
        assertEquals(MainActivity::class.java.name, add.component?.className)
        assertEquals(LaunchTarget.NEW_CHAT, LaunchTarget.fromIntent(launchTargetIntent(context, LaunchTarget.NEW_CHAT)))
    }
}
