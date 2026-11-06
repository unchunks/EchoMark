package com.unchunks.echomark.ui.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasAnyDescendant
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.ui.navigation.LaunchTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** ウィジェットの検索ボタンと、AI 処理の進み具合の欄(glance-appwidget-testing で構成を確かめる)。 */
@RunWith(AndroidJUnit4::class)
class RediscoverWidgetProcessingContentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val items = listOf(
        RediscoverWidgetItem(1, "Compose のパフォーマンス", "example.com"),
        RediscoverWidgetItem(2, "読書メモ", "メモ"),
        RediscoverWidgetItem(3, "Kotlin コルーチン入門", "kotlinlang.org")
    )

    private val processing = WidgetProcessing(running = 2, waiting = 3, fraction = 0.4f, remainingMinutes = 5)

    @Test
    fun ヘッダーに検索ボタンがあり_押すと検索を開く() = runGlanceAppWidgetUnitTest {
        setContext(context)
        val size = DpSize(260.dp, RediscoverWidgetData.MEDIUM_SIZE.height)
        setAppWidgetSize(size)
        provideComposable { RediscoverWidgetContent(items, RediscoverWidgetData.layoutFor(size)) }

        onNode(
            hasStartActivityClickAction(launchTargetIntent(context, LaunchTarget.SEARCH))
                .and(hasAnyDescendant(hasContentDescriptionEqualTo(SEARCH_LABEL)))
        ).assertExists()
        // 追加・チャットのボタンも残っている
        onNode(hasContentDescriptionEqualTo(ADD_LABEL)).assertExists()
        onNode(hasContentDescriptionEqualTo(CHAT_LABEL)).assertExists()
    }

    @Test
    fun 幅が最小のときは_ボタンが切れないよう検索ボタンを出さない() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(RediscoverWidgetData.MEDIUM_SIZE)
        provideComposable { RediscoverWidgetContent(items, RediscoverWidgetData.layoutFor(RediscoverWidgetData.MEDIUM_SIZE)) }

        onNode(hasContentDescriptionEqualTo(SEARCH_LABEL)).assertDoesNotExist()
        onNode(hasContentDescriptionEqualTo(ADD_LABEL)).assertExists()
        onNode(hasContentDescriptionEqualTo(CHAT_LABEL)).assertExists()
        assertFalse(RediscoverWidgetData.layoutFor(RediscoverWidgetData.SMALL_SIZE).showSearch)
        assertTrue(RediscoverWidgetData.layoutFor(DpSize(240.dp, 180.dp)).showSearch)
    }

    @Test
    fun 検索ボタンの起動先は_検索のLaunchTarget() {
        // Intent の比較は extra を見ないので、extra も確かめる
        assertEquals(LaunchTarget.SEARCH, LaunchTarget.fromIntent(launchTargetIntent(context, LaunchTarget.SEARCH)))
    }

    @Test
    fun 処理中は欄を出し_ブックマークを1件減らす() = runGlanceAppWidgetUnitTest {
        setContext(context)
        val size = DpSize(260.dp, RediscoverWidgetData.LARGE_SIZE.height)
        setAppWidgetSize(size)
        provideComposable {
            RediscoverWidgetContent(items, RediscoverWidgetData.layoutFor(size, hasProcessing = true), processing)
        }

        onNode(hasTextEqualTo(processing.title())).assertExists()
        onNode(hasTextEqualTo(processing.detail()!!)).assertExists()
        // 大きいサイズは 3 件 → 2 件
        onNode(hasTextEqualTo(items[0].title)).assertExists()
        onNode(hasTextEqualTo(items[1].title)).assertExists()
        onNode(hasTextEqualTo(items[2].title)).assertDoesNotExist()
    }

    @Test
    fun 進み具合の欄を押すと処理の状況の画面を開く() = runGlanceAppWidgetUnitTest {
        setContext(context)
        val size = DpSize(260.dp, RediscoverWidgetData.MEDIUM_SIZE.height)
        setAppWidgetSize(size)
        provideComposable {
            RediscoverWidgetContent(items, RediscoverWidgetData.layoutFor(size, hasProcessing = true), processing)
        }

        onNode(
            hasStartActivityClickAction(processingQueueIntent(context))
                .and(hasAnyDescendant(hasTextEqualTo(processing.title())))
        ).assertExists()
        // 読み上げ用の説明も付く
        onNode(hasContentDescriptionEqualTo(processing.description())).assertExists()
    }

    @Test
    fun 小さいサイズでは進み具合の欄を出さない() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(RediscoverWidgetData.SMALL_SIZE)
        provideComposable {
            RediscoverWidgetContent(
                items, RediscoverWidgetData.layoutFor(RediscoverWidgetData.SMALL_SIZE, hasProcessing = true), processing
            )
        }

        onNode(hasTextEqualTo(processing.title())).assertDoesNotExist()
        // ブックマークは 1 件のまま
        onNode(hasTextEqualTo(items[0].title)).assertExists()
    }

    @Test
    fun 処理が無ければ欄を出さない() = runGlanceAppWidgetUnitTest {
        setContext(context)
        val size = DpSize(260.dp, RediscoverWidgetData.MEDIUM_SIZE.height)
        setAppWidgetSize(size)
        provideComposable { RediscoverWidgetContent(items, RediscoverWidgetData.layoutFor(size, hasProcessing = false), null) }
        onNode(hasTextEqualTo(processing.title())).assertDoesNotExist()
        onNode(hasTextEqualTo(items[1].title)).assertExists()
    }

    @Test
    fun ブックマークが無くても進み具合の欄は出す() = runGlanceAppWidgetUnitTest {
        setContext(context)
        val size = DpSize(260.dp, RediscoverWidgetData.MEDIUM_SIZE.height)
        setAppWidgetSize(size)
        provideComposable {
            RediscoverWidgetContent(emptyList(), RediscoverWidgetData.layoutFor(size, hasProcessing = true), processing)
        }
        onNode(hasTextEqualTo(processing.title())).assertExists()
        onNode(hasTextEqualTo(EMPTY_MESSAGE)).assertExists()
    }

    @Test
    fun 棒を出さないのは_処理中のものが無いとき() = runGlanceAppWidgetUnitTest {
        // 待ちだけ(fraction なし)でも、件数と目安は出る
        val waitingOnly = WidgetProcessing(running = 0, waiting = 4, fraction = null, remainingMinutes = 8)
        setContext(context)
        val size = DpSize(260.dp, RediscoverWidgetData.MEDIUM_SIZE.height)
        setAppWidgetSize(size)
        provideComposable {
            RediscoverWidgetContent(items, RediscoverWidgetData.layoutFor(size, hasProcessing = true), waitingOnly)
        }
        onNode(hasTextEqualTo("AI 処理待ち 4件")).assertExists()
        onNode(hasTextEqualTo("残り約8分")).assertExists()
    }

    @Test
    fun 欄を出す大きさは_中くらい以上() {
        assertFalse(RediscoverWidgetData.layoutFor(RediscoverWidgetData.SMALL_SIZE, hasProcessing = true).showProcessing)
        assertTrue(RediscoverWidgetData.layoutFor(RediscoverWidgetData.MEDIUM_SIZE, hasProcessing = true).showProcessing)
        assertTrue(RediscoverWidgetData.layoutFor(RediscoverWidgetData.LARGE_SIZE, hasProcessing = true).showProcessing)
        assertFalse(RediscoverWidgetData.layoutFor(RediscoverWidgetData.LARGE_SIZE, hasProcessing = false).showProcessing)
        // 欄を出すと、件数は 1 件減る(小は 1 件のまま、中は 2 → 1、大は 3 → 2)
        assertEquals(1, RediscoverWidgetData.layoutFor(RediscoverWidgetData.MEDIUM_SIZE, hasProcessing = true).maxItems)
        assertEquals(2, RediscoverWidgetData.layoutFor(RediscoverWidgetData.LARGE_SIZE, hasProcessing = true).maxItems)
        assertEquals(1, RediscoverWidgetData.layoutFor(RediscoverWidgetData.SMALL_SIZE, hasProcessing = true).maxItems)
    }
}
