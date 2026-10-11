package com.unchunks.echomark.screenshot

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.unchunks.echomark.ui.widget.RediscoverWidgetContent
import com.unchunks.echomark.ui.widget.RediscoverWidgetData
import com.unchunks.echomark.ui.widget.RediscoverWidgetItem
import com.unchunks.echomark.ui.widget.RediscoverWidgetTheme
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * ホーム画面ウィジェットの見た目。Glance の中身を RemoteViews にして View に展開し、画像に撮る
 * (実際のホーム画面とは角丸・余白などが少し違うことがある)。
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RediscoverWidgetScreenshotTest {

    private val items = listOf(
        RediscoverWidgetItem(1, "Jetpack Compose のパフォーマンスを改善する10のヒント", "example.com"),
        RediscoverWidgetItem(2, "読書メモ: 『知の編集術』", "メモ"),
        RediscoverWidgetItem(3, "Kotlin コルーチンの構造化された並行性", "kotlinlang.org")
    )

    // 幅は中身が広がるので、一般的な 3〜4 マス幅(260dp)と最小幅(180dp)で撮る
    @Test
    fun large() = capture("rediscover_widget_large", DpSize(260.dp, RediscoverWidgetData.LARGE_SIZE.height), items)

    @Test
    fun medium() = capture("rediscover_widget_medium", DpSize(260.dp, RediscoverWidgetData.MEDIUM_SIZE.height), items)

    @Test
    fun smallMinimum() = capture("rediscover_widget_small", RediscoverWidgetData.SMALL_SIZE, items)

    @Test
    fun largeMinimumWidth() = capture("rediscover_widget_large_narrow", RediscoverWidgetData.LARGE_SIZE, items)

    @Test
    fun empty() = capture("rediscover_widget_empty", DpSize(260.dp, RediscoverWidgetData.MEDIUM_SIZE.height), emptyList())

    /** ライト・ダーク(ブランド配色)とダイナミックカラー(ライト)で撮る。 */
    private fun capture(name: String, size: DpSize, items: List<RediscoverWidgetItem>) {
        val base: Context = ApplicationProvider.getApplicationContext()
        render(base, night = false, dynamicColor = false, size, items).captureRoboImage("${name}_light.png")
        render(base, night = true, dynamicColor = false, size, items).captureRoboImage("${name}_dark.png")
        render(base, night = false, dynamicColor = true, size, items).captureRoboImage("${name}_dynamic.png")
    }

    private fun render(
        base: Context,
        night: Boolean,
        dynamicColor: Boolean,
        size: DpSize,
        items: List<RediscoverWidgetItem>
    ): Bitmap {
        val config = Configuration(base.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val context = base.createConfigurationContext(config)
        val remoteViews = runBlocking {
            GlanceRemoteViews().compose(context, size) {
                RediscoverWidgetTheme(dynamicColor) { RediscoverWidgetContent(items, RediscoverWidgetData.layoutFor(size)) }
            }.remoteViews
        }
        val density = context.resources.displayMetrics.density
        val width = (size.width.value * density).roundToInt()
        val height = (size.height.value * density).roundToInt()
        // ホーム画面の壁紙の代わりに灰色の台紙に載せる
        val host = FrameLayout(context).apply { setBackgroundColor(if (night) Color.DKGRAY else Color.LTGRAY) }
        val widget = remoteViews.apply(context, host)
        host.addView(widget, FrameLayout.LayoutParams(width, height))
        val margin = (8 * density).roundToInt()
        host.setPadding(margin, margin, margin, margin)
        host.measure(
            View.MeasureSpec.makeMeasureSpec(width + margin * 2, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height + margin * 2, View.MeasureSpec.EXACTLY)
        )
        host.layout(0, 0, host.measuredWidth, host.measuredHeight)
        // Roborazzi の View 撮影は Activity が要るため、Bitmap に描いて撮る
        return Bitmap.createBitmap(host.measuredWidth, host.measuredHeight, Bitmap.Config.ARGB_8888)
            .also { host.draw(Canvas(it)) }
    }
}
