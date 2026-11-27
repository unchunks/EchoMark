package com.unchunks.echomark.screenshot.store

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.screenshot.ScreenshotRule
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Google Play の掲載用の画像。何を描くか(見出し・画面・フォント・色など)は tools/store-assets/store-assets.json で決める。
 * tools/store-assets/make-store-assets.ps1 がこれらを動かし、fastlane/metadata/android/ja-JP/images/ へ写す。
 * 手順は docs/store-listing.md。
 *
 * 画素数は「dp × 画面密度」で決まるので、画像の種類ごとに @Config の qualifiers で密度を選んでいる。
 * - スマートフォン: 360×640dp × xxhdpi(3倍) = 1080×1920 px(9:16。おすすめ枠に載る条件の最小サイズ)
 * - タブレット: 1280×720dp × hdpi(1.5倍) = 1920×1080 px(16:9)
 * - フィーチャー グラフィック: 512×250dp × xhdpi(2倍) = 1024×500 px
 * - アイコン: 256×256dp × xhdpi(2倍) = 512×512 px
 *
 * 大きな画像を描くので、ふだんの単体テスト(CI)では飛ばし、画像を記録するとき(Roborazzi の記録)だけ描く。
 */

/** 画像を記録するときだけ描く。Roborazzi の Gradle プラグインが、記録のときにこのシステムプロパティを渡す */
internal fun assumeStoreRecording() = assumeTrue(
    "ストア用の画像は Roborazzi の記録のときだけ描く",
    System.getProperty("roborazzi.test.record") == "true" || System.getenv("ECHOMARK_STORE_ASSETS") == "1"
)

/** アイコンとフィーチャー グラフィック */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoreGraphicsScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    @Before
    fun onlyWhenRecording() = assumeStoreRecording()

    /** Play が角を丸めるので、正方形のまま隅まで塗る(影や角丸は付けない) */
    @Test
    @Config(qualifiers = "w256dp-h256dp-xhdpi")
    fun icon() = screenshot.captureStoreImage("icon", 256, 256) {
        AppIcon(256.dp)
    }

    /**
     * 動画を載せると中央に再生ボタンが重なるので、中央には何も置かない(左に名前、右に画面)。
     * 端は切れることがあるため、文字は端から離す。
     */
    @Test
    @Config(qualifiers = "w512dp-h250dp-xhdpi")
    fun featureGraphic() = screenshot.captureStoreImage("feature_graphic", 512, 250) {
        val config = StoreConfig.current
        Box(Modifier.size(512.dp, 250.dp).clipToBounds()) {
            EchoBackground(center = Offset(0.8f, 0.5f), rings = 6)
            Column(Modifier.align(Alignment.CenterStart).padding(start = 36.dp).widthIn(max = 200.dp)) {
                AppIcon(52.dp, cornerRadius = 14.dp)
                Spacer(Modifier.height(12.dp))
                StoreText(config.featureTitle, config.headline, sizeSp = 30f)
                Spacer(Modifier.height(6.dp))
                StoreText(config.featureTagline, config.caption, sizeSp = 14f)
            }
            PhoneFrame(
                scale = 0.4f,
                modifier = Modifier.offset(x = 326.dp, y = 34.dp).rotate(-8f)
            ) {
                StoreScreens.phoneScreens.getValue("home").content()
            }
        }
    }
}

/** スマートフォンのスクリーンショット(設定の phone[] を1枚ずつ) */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xxhdpi")
class StorePhoneScreenshotTest(private val shot: StoreShot) {

    @get:Rule
    val screenshot = ScreenshotRule()

    @Before
    fun onlyWhenRecording() = assumeStoreRecording()

    @Test
    fun capture() {
        val screen = StoreScreens.phoneScreens[shot.screen]
            ?: error("phone の screen「${shot.screen}」はありません。使える画面: ${StoreScreens.phoneScreens.keys.joinToString()}")
        val scrollDp = shot.scrollDp ?: screen.scroll?.phoneDp ?: 0f
        screenshot.captureStoreImage(
            "phone/${shot.file}", PHONE_SHOT_WIDTH_DP, PHONE_SHOT_HEIGHT_DP,
            beforeCapture = { if (scrollDp > 0f) scrollContaining(screen.scroll?.text ?: "", scrollDp) }
        ) {
            PhoneShot(shot.headline, shot.caption, screenBackground = MaterialTheme.colorScheme.surface) { screen.content() }
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun shots(): List<Array<Any>> = StoreConfig.current.phone.map { arrayOf(it) }
    }
}

/** タブレット(横向き)のスクリーンショット。大きな画面では文字が切れることがあるので、画面だけを載せる */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h720dp-hdpi")
class StoreTabletScreenshotTest(private val shot: StoreShot) {

    @get:Rule
    val screenshot = ScreenshotRule()

    @Before
    fun onlyWhenRecording() = assumeStoreRecording()

    @Test
    fun capture() {
        val screen = StoreScreens.tabletScreens[shot.screen]
            ?: error("tablet の screen「${shot.screen}」はありません。使える画面: ${StoreScreens.tabletScreens.keys.joinToString()}")
        val scrollDp = shot.scrollDp ?: screen.scroll?.tabletDp ?: 0f
        val layout = StoreScreens.layoutOf(TABLET_WIDTH_DP, TABLET_HEIGHT_DP)
        screenshot.captureStoreImage(
            "tablet/${shot.file}", TABLET_WIDTH_DP, TABLET_HEIGHT_DP,
            beforeCapture = { if (scrollDp > 0f) scrollContaining(screen.scroll?.text ?: "", scrollDp) }
        ) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                WithAppFont { screen.content(layout) }
            }
        }
    }

    companion object {
        private const val TABLET_WIDTH_DP = 1280
        private const val TABLET_HEIGHT_DP = 720

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun shots(): List<Array<Any>> = StoreConfig.current.tablet.map { arrayOf(it) }
    }
}
