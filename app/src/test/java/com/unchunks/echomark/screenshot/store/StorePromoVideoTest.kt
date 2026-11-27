package com.unchunks.echomark.screenshot.store

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.unchunks.echomark.screenshot.ScreenshotRule
import com.unchunks.echomark.ui.share.ShareSaveStatus
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToLong

/**
 * Google Play の紹介動画(YouTube に上げる 1920×1080・30fps)のコマを、本物の画面で描いて連番の PNG にする。
 *
 * 1コマ描くたびに Compose の時計を 1/30 秒進めるので、画面の中のアニメーション(シートの切り替えなど)も
 * 実際の速さで動く。コマ数が多く時間がかかるため、環境変数 ECHOMARK_STORE_VIDEO=1 のときだけ動く
 * (ふだんの単体テスト・CI では飛ばす)。MP4 にする手順は tools/store-assets/make-store-assets.ps1。
 *
 * Play のおすすめに合わせ、最初の 10 秒以内に実際の画面を見せ、ロゴだけの場面は短くしている。
 * 音声は入れない(Play では消音で自動再生されるため、説明は字幕の見出しで伝える)。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// 640×360dp × xxhdpi(3倍) = 1920×1080 px
@Config(qualifiers = "w640dp-h360dp-xxhdpi")
class StorePromoVideoTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    @Test
    fun frames() {
        assumeTrue("ECHOMARK_STORE_VIDEO=1 のときだけ動画のコマを書き出す", System.getenv("ECHOMARK_STORE_VIDEO") == "1")
        var time by mutableLongStateOf(0L)
        val rule = screenshot.composeRule
        rule.mainClock.autoAdvance = false
        rule.setContent {
            StoreEnvironment {
                Box(Modifier.requiredSize(VIDEO_WIDTH_DP.dp, VIDEO_HEIGHT_DP.dp).testTag(VIDEO_TAG)) {
                    PromoFrame(time)
                }
            }
        }
        val frameCount = (PromoTimeline.TOTAL_MILLIS * FPS / 1000).toInt()
        var clock = 0L
        for (frame in 0 until frameCount) {
            val previous = time
            time = (frame * 1000.0 / FPS).roundToLong()
            Snapshot.sendApplyNotifications()
            // 画面の中のアニメーションも動画の時間と同じだけ進める
            rule.mainClock.advanceTimeBy(time - clock + if (frame == 0) WARM_UP_MILLIS else 0L)
            clock = time
            PromoTimeline.scrolls.forEach { scroll ->
                val delta = scroll.dpAt(time) - scroll.dpAt(previous)
                if (delta != 0f) screenshot.scrollContaining(scroll.text, delta)
            }
            rule.onNodeWithTag(VIDEO_TAG).captureRoboImage("video/frame_%05d.png".format(frame))
        }
    }

    private companion object {
        const val FPS = 30
        const val VIDEO_WIDTH_DP = 640
        const val VIDEO_HEIGHT_DP = 360
        const val VIDEO_TAG = "video_root"

        /** 最初のコマの前に、画像の読み込みなどを済ませておく時間 */
        const val WARM_UP_MILLIS = 600L
    }
}

/**
 * 1つの場面(見出し + スマートフォンの画面)。[screen] には場面の始まりからの経過時間(ミリ秒)を渡す。
 * 見出しは設定(store-assets.json の video.scenes の [key])から読む。
 */
private class PromoScene(
    val key: String,
    val startMillis: Long,
    val endMillis: Long,
    val screen: @Composable (elapsed: Long) -> Unit
) {
    val headline: String get() = StoreConfig.current.video.caption(key).headline
    val caption: String get() = StoreConfig.current.video.caption(key).caption
}

/** 場面の途中で、[text] を含む画面を [totalDp] だけゆっくりスクロールする */
private class PromoScroll(val startMillis: Long, val durationMillis: Long, val text: String, val totalDp: Float) {
    /** [t] の時点までにスクロールし終えている量 */
    fun dpAt(t: Long): Float = ease(progress(t, startMillis, durationMillis)) * totalDp
}

private object PromoTimeline {
    const val INTRO_END = 2_400L
    const val OUTRO_START = 31_000L
    const val TOTAL_MILLIS = 34_000L

    /** 場面の切り替え(重ねて溶かす)の長さ */
    const val FADE = 450L

    /** 見出しの切り替え(消えてから出る)の長さ */
    const val CAPTION_FADE = 350L

    private val answer = StoreSamples.CHAT_ANSWER

    val scenes = listOf(
        PromoScene("share", INTRO_END, 7_200) { t ->
            StoreScreens.Share(
                status = if (t < 3_000) ShareSaveStatus.Editing else ShareSaveStatus.Saved(isDuplicate = false),
                tags = if (t < 1_200) "旅行" else "旅行 京都"
            )
        },
        PromoScene("organize", 7_200, 11_600) { t ->
            val done = t >= 1_800
            val state = StoreScreens.homeState.copy(
                bookmarks = if (done) {
                    listOf(StoreSamples.kyoto.copy(id = StoreSamples.justSaved.id, createdAt = StoreSamples.NOW - 60_000)) +
                        StoreSamples.library.drop(1).filter { it.id != StoreSamples.kyoto.id }
                } else {
                    StoreSamples.library
                }
            )
            StoreScreens.TopLevel("bookmarks") { StoreScreens.Home(state, modifier = it) }
        },
        PromoScene("detail", 11_600, 15_600) {
            StoreScreens.Detail()
        },  // 開いてから少しして、AI の要約が見えるところまでスクロールする(下の scrolls)
        PromoScene("search", 15_600, 20_800) { t ->
            val query = StoreSamples.SEARCH_QUERY
            val typed = ((t - 500) / 170).coerceIn(0, query.length.toLong()).toInt()
            val state = StoreScreens.searchState(query.take(typed), showResults = t >= 500 + 170L * query.length + 400)
            StoreScreens.TopLevel("bookmarks") { StoreScreens.Home(state, modifier = it) }
        },
        PromoScene("chat", 20_800, 26_400) { t ->
            val streamStart = 900L
            val streamMillis = 3_000L
            val state = when {
                t < streamStart -> StoreScreens.chatState(answer = null, streaming = "")
                t < streamStart + streamMillis -> {
                    val chars = (answer.length * (t - streamStart) / streamMillis).toInt()
                    StoreScreens.chatState(answer = null, streaming = withoutOpenBold(answer.take(chars)))
                }
                else -> StoreScreens.chatState()
            }
            StoreScreens.Chat(state)
        },
        PromoScene("graph", 26_400, 28_800) {
            StoreScreens.Graph()
        },
        // 端末内とクラウド(CLOUD_AI_ENABLED = true)の AI を並べて打ち出す。画面は初回の「AI の準備」で、両方の選択肢が写る
        PromoScene("ai", 28_800, OUTRO_START) {
            StoreScreens.AiChoice()
        }
    )

    val scrolls = listOf(PromoScroll(startMillis = 12_700, durationMillis = 1_200, text = "ブラウザで開く", totalDp = 205f))
}

/**
 * 流れている途中の答えで、閉じていない太字の印(`**`)を消す。
 * 途中で切ると「**大」のように印がそのまま見えるため(動画では数コマだけだが目に付く)。
 */
private fun withoutOpenBold(partial: String): String {
    val marks = Regex("\\*\\*").findAll(partial).count()
    return if (marks % 2 == 0) partial else partial.removeRange(partial.lastIndexOf("**"), partial.lastIndexOf("**") + 2)
}

/** 0〜1 に収めた進み具合 */
private fun progress(t: Long, start: Long, duration: Long): Float =
    ((t - start).toFloat() / duration).coerceIn(0f, 1f)

/** ゆっくり始まり、ゆっくり止まる */
private fun ease(x: Float): Float = x * x * (3 - 2 * x)

@Composable
private fun PromoFrame(t: Long) {
    val timeline = PromoTimeline
    Box(Modifier.size(640.dp, 360.dp).clipToBounds()) {
        EchoBackground(center = Offset(0.72f, 0.55f), rings = 6)

        // ---- はじめとおわりのロゴ ----
        val introOut = 1f - ease(progress(t, timeline.INTRO_END - 500, 500))
        val introIn = ease(progress(t, 0, 600))
        val outroIn = ease(progress(t, timeline.OUTRO_START + 200, 700))
        val logoAlpha = maxOf(introIn * introOut, outroIn)
        if (logoAlpha > 0f) {
            val rise = if (t < timeline.OUTRO_START) (1f - introIn) else (1f - outroIn)
            Logo(Modifier.align(Alignment.Center).graphicsLayer { alpha = logoAlpha; translationY = rise * 30f })
        }

        // ---- 見出し(左) ----
        timeline.scenes.forEach { scene ->
            val fadeIn = ease(progress(t, scene.startMillis + 150, timeline.CAPTION_FADE))
            val fadeOut = 1f - ease(progress(t, scene.endMillis - timeline.CAPTION_FADE, timeline.CAPTION_FADE))
            val alpha = fadeIn * fadeOut
            if (alpha > 0f) {
                Caption(
                    scene.headline,
                    scene.caption,
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 52.dp)
                        // 右のスマートフォン(左端が 390dp ほど)に重ならず、見出しが途中で折り返さない幅
                        .width(325.dp)
                        .graphicsLayer { this.alpha = alpha; translationY = (1f - fadeIn) * 36f }
                )
            }
        }

        // ---- スマートフォン(右)。はじめは下から入り、おわりは下へ抜ける ----
        val phoneIn = ease(progress(t, timeline.INTRO_END - 600, 900))
        val phoneOut = ease(progress(t, timeline.OUTRO_START - 200, 700))
        val phoneVisible = phoneIn > 0f && phoneOut < 1f
        if (phoneVisible) {
            val scale = PHONE_SCALE
            PhoneFrame(
                scale = scale,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 84.dp)
                    .graphicsLayer {
                        translationY = (1f - phoneIn) * size.height * 1.2f + phoneOut * size.height * 1.2f
                        // 場面ごとに少しずつ寄る(止まった画に見えないように)
                        val scene = timeline.scenes.lastOrNull { t >= it.startMillis }
                        val zoom = if (scene == null) 0f else progress(t, scene.startMillis, scene.endMillis - scene.startMillis)
                        scaleX = 1f + zoom * 0.035f
                        scaleY = scaleX
                    }
            ) {
                PhoneScreens(t)
            }
        }
    }
}

/** 場面の画面を、切り替えのときだけ前の画面の上に重ねて溶かす */
@Composable
private fun PhoneScreens(t: Long) {
    val scenes = PromoTimeline.scenes
    Box(Modifier.fillMaxSize()) {
        scenes.forEachIndexed { index, scene ->
            // 切り替えの少し前から組み立てておき、画像の読み込みなどで最初のコマが欠けないようにする
            val composeFrom = scene.startMillis - PromoTimeline.FADE - 400
            val composeUntil = (scenes.getOrNull(index + 1)?.startMillis ?: PromoTimeline.OUTRO_START) + PromoTimeline.FADE
            if (t in composeFrom..composeUntil) {
                val alpha = if (index == 0) 1f else ease(progress(t, scene.startMillis - PromoTimeline.FADE, PromoTimeline.FADE))
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { this.alpha = alpha }
                ) {
                    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                        scene.screen((t - scene.startMillis).coerceAtLeast(0))
                    }
                }
            }
        }
    }
}

/** 見出し(フォント・色はスクリーンショットと同じ設定。大きさは video.headlineSize・captionSize) */
@Composable
private fun Caption(headline: String, caption: String, modifier: Modifier = Modifier) {
    val video = StoreConfig.current.video
    Headline(
        headline, caption, modifier,
        align = TextAlign.Start,
        headlineSizeSp = video.headlineSizeSp,
        captionSizeSp = video.captionSizeSp,
        gapDp = 10f
    )
}

/** はじめとおわりのロゴ(video.title・video.tagline) */
@Composable
private fun Logo(modifier: Modifier = Modifier) {
    val config = StoreConfig.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AppIcon(76.dp, cornerRadius = 20.dp)
        Spacer(Modifier.height(14.dp))
        StoreText(config.video.title, config.headline, sizeSp = 34f, align = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        StoreText(config.video.tagline, config.caption, sizeSp = 15f, align = TextAlign.Center)
    }
}

/** 縦 360dp の画面に、上下に少し余白を残して枠が収まる倍率 */
private const val PHONE_SCALE = 0.39f
