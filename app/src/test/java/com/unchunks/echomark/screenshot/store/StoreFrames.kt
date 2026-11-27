package com.unchunks.echomark.screenshot.store

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.github.takahirom.roborazzi.captureRoboImage
import com.unchunks.echomark.R
import com.unchunks.echomark.screenshot.ScreenshotRule
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import kotlin.math.abs

// ---- 撮り方 ----

/**
 * ストア用の画像を1枚、[widthDp]×[heightDp] の大きさで撮って `store/<name>.png` に保存する。
 * 画像の画素数は dp × テストの画面密度(@Config の qualifiers)で決まる。ライトテーマだけで撮る。
 * 撮る前に画面をスクロールしたいときは [beforeCapture] で [scrollContaining] を呼ぶ。
 * 1テストにつき1回だけ呼べる。
 */
@OptIn(ExperimentalCoilApi::class)
internal fun ScreenshotRule.captureStoreImage(
    name: String,
    widthDp: Int,
    heightDp: Int,
    beforeCapture: ScreenshotRule.() -> Unit = {},
    content: @Composable () -> Unit
) {
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
        StoreEnvironment {
            Box(Modifier.requiredSize(widthDp.dp, heightDp.dp).testTag(STORE_TAG)) { content() }
        }
    }
    composeRule.mainClock.advanceTimeBy(SETTLE_MILLIS)
    beforeCapture()
    composeRule.mainClock.advanceTimeBy(SETTLE_MILLIS)
    composeRule.onNodeWithTag(STORE_TAG).captureRoboImage("store/$name.png")
}

/**
 * [text] を含むスクロールできる領域を、下へ [dp] だけスクロールする(長い画面の見せたい所を枠に収める)。
 * 枠に入れて縮めていても、スクロールの量は縮める前の大きさで数える。
 */
internal fun ScreenshotRule.scrollContaining(text: String, dp: Float) {
    val px = dp * composeRule.density.density
    composeRule
        .onAllNodes(hasScrollAction() and hasAnyDescendant(hasText(text, substring = true)))
        .onFirst()
        .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, px) }
}

/** プレビュー扱い(画像は通信しない)にし、ブランド配色のライトテーマで描く */
@OptIn(ExperimentalCoilApi::class)
@Composable
internal fun StoreEnvironment(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalInspectionMode provides true,
        LocalAsyncImagePreviewHandler provides StoreImageHandler
    ) {
        // アプリの画面のデザインとライト/ダークは設定で選ぶ(既定はクラシックのライト)。画像はブランド配色で固定する
        val config = StoreConfig.current
        EchoMarkTheme(darkTheme = config.appDarkTheme, dynamicColor = false, designStyle = config.appDesign) {
            content()
        }
    }
}

/**
 * 枠の中のアプリの画面だけ、設定(app.font)の書体で描く。Material 3 の文字の型(typography)の書体を差し替えるので、
 * 書体を直に指定している所(ペーパーのデザインの明朝など)はそのまま。見出しやステータスバーには効かせない。
 */
@Composable
internal fun WithAppFont(content: @Composable () -> Unit) {
    val family = StoreConfig.current.appFont.fontFamily
    if (family == null) {
        content()
        return
    }
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography.withFontFamily(family),
        content = content
    )
}

private fun Typography.withFontFamily(family: FontFamily): Typography {
    fun TextStyle.withFamily() = copy(fontFamily = family)
    return copy(
        displayLarge = displayLarge.withFamily(),
        displayMedium = displayMedium.withFamily(),
        displaySmall = displaySmall.withFamily(),
        headlineLarge = headlineLarge.withFamily(),
        headlineMedium = headlineMedium.withFamily(),
        headlineSmall = headlineSmall.withFamily(),
        titleLarge = titleLarge.withFamily(),
        titleMedium = titleMedium.withFamily(),
        titleSmall = titleSmall.withFamily(),
        bodyLarge = bodyLarge.withFamily(),
        bodyMedium = bodyMedium.withFamily(),
        bodySmall = bodySmall.withFamily(),
        labelLarge = labelLarge.withFamily(),
        labelMedium = labelMedium.withFamily(),
        labelSmall = labelSmall.withFamily()
    )
}

private const val STORE_TAG = "store_root"
private const val SETTLE_MILLIS = 1_000L

// ---- 背景 ----

/** 背景のグラデーション(設定の background.colors。左上から右下へ) */
internal val storeBackground: Brush
    get() {
        val colors = StoreConfig.current.backgroundColors
        return if (colors.size == 1) SolidColor(colors.single()) else Brush.linearGradient(colors, start = Offset.Zero, end = Offset.Infinite)
    }

/**
 * 背景に、アイコンと同じ「響き」の波紋を薄く重ねる。[center] は波紋の中心(0〜1 の割合)。
 * 設定の background.rings が false なら、グラデーションだけにする。
 */
@Composable
internal fun EchoBackground(modifier: Modifier = Modifier, center: Offset = Offset(0.5f, 0.62f), rings: Int = 5) {
    val showRings = StoreConfig.current.backgroundRings
    Canvas(modifier.fillMaxSize().background(storeBackground)) {
        if (!showRings) return@Canvas
        val c = Offset(size.width * center.x, size.height * center.y)
        val step = size.minDimension * 0.22f
        for (i in 1..rings) {
            drawCircle(
                color = Color.White.copy(alpha = 0.075f - i * 0.009f),
                radius = step * i,
                center = c,
                style = Stroke(width = step * 0.08f)
            )
        }
    }
}

// ---- スマートフォンの枠 ----

/** アプリの画面の大きさ(dp)。一般的なスマートフォンの幅で描き、枠ごと縮小して載せる */
internal const val APP_WIDTH_DP = 400
internal const val APP_HEIGHT_DP = 780
private const val STATUS_BAR_DP = 28
private const val GESTURE_BAR_DP = 18
private const val BEZEL_DP = 9
internal const val DEVICE_WIDTH_DP = APP_WIDTH_DP + BEZEL_DP * 2
internal const val DEVICE_HEIGHT_DP = APP_HEIGHT_DP + STATUS_BAR_DP + GESTURE_BAR_DP + BEZEL_DP * 2

/**
 * スマートフォンの枠に入れたアプリの画面。中身は [APP_WIDTH_DP]×[APP_HEIGHT_DP] で描き、枠ごと [scale] 倍に縮める
 * (文字は縮めた後の大きさで描かれるので、ぼやけない)。
 * @param screenBackground ステータスバーとジェスチャーバーの地色(アプリの画面の地色に合わせる)
 */
@Composable
internal fun PhoneFrame(
    scale: Float,
    modifier: Modifier = Modifier,
    screenBackground: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit
) {
    Box(
        modifier.size(DEVICE_WIDTH_DP.dp * scale, DEVICE_HEIGHT_DP.dp * scale),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .requiredSize(DEVICE_WIDTH_DP.dp, DEVICE_HEIGHT_DP.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin.Center
                }
                .shadow(28.dp, RoundedCornerShape(48.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .clip(RoundedCornerShape(48.dp))
                .background(StoreConfig.current.deviceFrameColor)
                .padding(BEZEL_DP.dp)
                .clip(RoundedCornerShape(40.dp))
                .background(screenBackground)
        ) {
            Column {
                StatusBar(Modifier.height(STATUS_BAR_DP.dp))
                Box(Modifier.size(APP_WIDTH_DP.dp, APP_HEIGHT_DP.dp).clipToBounds()) { WithAppFont { content() } }
                GestureBar(Modifier.height(GESTURE_BAR_DP.dp))
            }
        }
    }
}

@Composable
private fun StatusBar(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface
    Row(
        modifier.fillMaxWidth().padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(StoreConfig.current.statusBarTime, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        // パンチホールのカメラ
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(50)).background(StoreConfig.current.deviceFrameColor))
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Wifi, null, Modifier.size(15.dp), tint = color)
            Icon(Icons.Filled.SignalCellular4Bar, null, Modifier.size(14.dp), tint = color)
            Icon(Icons.Filled.BatteryFull, null, Modifier.size(15.dp), tint = color)
        }
    }
}

@Composable
private fun GestureBar(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(108.dp, 4.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        )
    }
}

// ---- スマートフォンのスクリーンショット(見出し + 枠に入れた画面) ----

/** 9:16 の縦長(360dp × 640dp。xxhdpi で 1080 × 1920 px) */
internal const val PHONE_SHOT_WIDTH_DP = 360
internal const val PHONE_SHOT_HEIGHT_DP = 640

/**
 * ストアのスマートフォン用スクリーンショット1枚。上に見出し(画像の 20% 以内)、その下に枠に入れたアプリの画面。
 * 見出しは画面の内容をそのまま言い表す短い文にする(Play のおすすめに合わせ、行動を促す言葉や順位の主張は入れない)。
 */
@Composable
internal fun PhoneShot(
    headline: String,
    caption: String,
    screenBackground: Color = MaterialTheme.colorScheme.surface,
    screen: @Composable () -> Unit
) {
    Box(Modifier.size(PHONE_SHOT_WIDTH_DP.dp, PHONE_SHOT_HEIGHT_DP.dp)) {
        EchoBackground()
        Column(
            Modifier.fillMaxSize().padding(top = 26.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Headline(headline, caption, Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(14.dp))
            // 見出しの文字の大きさを設定で変えても、枠が残りの高さに収まるよう倍率を決める(幅は画像の 7 割まで)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                val scale = minOf(maxHeight / DEVICE_HEIGHT_DP.dp, maxWidth * 0.7f / DEVICE_WIDTH_DP.dp)
                PhoneFrame(scale = scale, screenBackground = screenBackground) { screen() }
            }
        }
    }
}

/**
 * 見出しと説明。フォント・太さ・大きさ・色は設定(text.headline・text.caption)で決める。
 * [headlineSizeSp] / [captionSizeSp] を渡すと、大きさだけを変える(紹介動画は画面が横長なので大きくする)。
 */
@Composable
internal fun Headline(
    headline: String,
    caption: String,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Center,
    headlineSizeSp: Float? = null,
    captionSizeSp: Float? = null,
    gapDp: Float = 5f
) {
    val config = StoreConfig.current
    Column(modifier, horizontalAlignment = if (align == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start) {
        StoreText(headline, config.headline, headlineSizeSp, align)
        if (caption.isNotEmpty()) {
            Spacer(Modifier.height(gapDp.dp))
            StoreText(caption, config.caption, captionSizeSp, align)
        }
    }
}

/** 設定の文字の見た目([StoreTextStyle])で文字を描く。行の高さは文字の大きさの 1.3 倍 */
@Composable
internal fun StoreText(text: String, style: StoreTextStyle, sizeSp: Float? = null, align: TextAlign = TextAlign.Start) {
    val size = sizeSp ?: style.sizeSp
    Text(
        text,
        color = style.color,
        fontSize = size.sp,
        lineHeight = (size * 1.3f).sp,
        fontWeight = style.fontWeight,
        fontFamily = style.fontFamily,
        textAlign = align
    )
}

// ---- アイコン ----

/** ランチャーアイコン(アダプティブアイコンの背景 + 前景)を正方形のまま描く。108dp の層の中央 72dp が見える範囲 */
@Composable
internal fun AppIcon(size: Dp, modifier: Modifier = Modifier, cornerRadius: Dp = 0.dp) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(cornerRadius)),
        contentAlignment = Alignment.Center
    ) {
        val layer = size * 108 / 72
        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.requiredSize(layer))
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(layer))
    }
}

// ---- 共有シートを出す前のブラウザ(仮の画面) ----

/** ブラウザで記事を開いている画面の代わり。実在のブラウザに似せず、アドレスバーと記事の形だけを描く */
@Composable
internal fun FakeBrowserPage(host: String, title: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surface, modifier = modifier.fillMaxSize()) {
        Column {
            Box(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(colors.surfaceContainerHigh)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(host, color = colors.onSurfaceVariant, fontSize = 15.sp)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .background(Brush.linearGradient(listOf(Color(0xFFE9724C), Color(0xFFB8323F), Color(0xFF5B2A4E))))
            )
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, color = colors.onSurface, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
                repeat(7) { i ->
                    Box(
                        Modifier
                            .fillMaxWidth(if (i % 3 == 2) 0.6f else 1f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.surfaceContainerHighest)
                    )
                }
            }
        }
    }
}

/** 下から出るシートの形(角の丸い上端とつまみ)で中身を囲む */
@Composable
internal fun BoxScope.BottomSheetMock(content: @Composable () -> Unit) {
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.38f)))
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .padding(vertical = 14.dp)
                    .width(32.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            )
            content()
        }
    }
}

// ---- リンク先の画像(仮) ----

/**
 * リンク先の OG 画像の代わりに、話題に合った色のグラデーションを描く(通信はしない)。
 * 全部同じ単色だと一覧が単調に見えるため、URL の内容で色を変える。
 */
@OptIn(ExperimentalCoilApi::class)
internal val StoreImageHandler = AsyncImagePreviewHandler { request ->
    val url = request.data.toString()
    val palette = when {
        "sleep" in url -> listOf(0xFF2E3A8C, 0xFF6F7FD8, 0xFFC9B8F0)
        "kyoto" in url || "leaves" in url -> listOf(0xFFF2A65A, 0xFFE0533D, 0xFF7A2335)
        "meal" in url || "recipe" in url -> listOf(0xFFF6D365, 0xFFF29E4C, 0xFF7DB46C)
        "pomodoro" in url || "work" in url -> listOf(0xFF4FB3BF, 0xFF2D7D8C, 0xFF1D4E63)
        "ai" in url || "tech" in url -> listOf(0xFF8E7CF0, 0xFF4E54C8, 0xFF1F2A6B)
        "stretch" in url -> listOf(0xFFB8E1C4, 0xFF6CC3A0, 0xFF2F8F7E)
        "garden" in url -> listOf(0xFFCFE8A9, 0xFF7FBF5F, 0xFF3D7A3A)
        else -> listOf(0xFF9DB4C0, 0xFF5C6B73, 0xFF253237)
    }
    gradientBitmap(palette.map { it.toInt() }, seed = abs(url.hashCode())).asImage()
}

private fun gradientBitmap(colors: List<Int>, seed: Int): Bitmap {
    val w = 640
    val h = 360
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), colors.toIntArray(), null, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    // 写真のボケのような、やわらかい光の玉
    repeat(5) { i ->
        val x = ((seed / (i + 1)) % w).toFloat()
        val y = ((seed / (i + 3)) % h).toFloat()
        val r = 60f + (seed / (i + 7)) % 90
        paint.shader = RadialGradient(x, y, r, intArrayOf(0x55FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, r, paint)
    }
    return bitmap
}
