package com.unchunks.echomark.screenshot.store

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.unchunks.echomark.domain.repository.DesignStyle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ストアの画像と紹介動画の設定(`tools/store-assets/store-assets.json`)。
 * 見出しのフォント・大きさ・色、背景の色、端末の枠、アプリのデザイン、各画像の見出しと画面などを、コードを変えずに変えられる。
 * 項目の説明は docs/store-listing.md の「設定」。
 */
internal data class StoreConfig(
    val headline: StoreTextStyle,
    val caption: StoreTextStyle,
    val backgroundColors: List<Color>,
    val backgroundRings: Boolean,
    val deviceFrameColor: Color,
    val statusBarTime: String,
    val appDesign: DesignStyle,
    val appDarkTheme: Boolean,
    val featureTitle: String,
    val featureTagline: String,
    val phone: List<StoreShot>,
    val tablet: List<StoreShot>,
    val video: StoreVideoConfig
) {
    companion object {
        /** 設定のファイル。リポジトリの中の決まった場所にある */
        val file: File by lazy { findRepoFile(CONFIG_PATH) }

        /** 読み込んだ設定。テストの間は1回だけ読む */
        val current: StoreConfig by lazy { parse(file) }

        private const val CONFIG_PATH = "tools/store-assets/store-assets.json"

        /**
         * Gradle の単体テストは app/ で動くので、親をたどってリポジトリの中のファイルを探す。
         * 見つからなければ、どこを探したかを添えて失敗させる。
         */
        private fun findRepoFile(path: String): File {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            return generateSequence(start) { it.parentFile }
                .map { File(it, path) }
                .firstOrNull { it.isFile }
                ?: error("$path が見つかりません($start から親をたどって探しました)")
        }

        fun parse(file: File): StoreConfig {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val baseDir = file.parentFile
            val text = root.getJSONObject("text")
            val background = root.getJSONObject("background")
            val device = root.getJSONObject("device")
            val app = root.getJSONObject("app")
            val feature = root.getJSONObject("featureGraphic")
            val video = root.getJSONObject("video")
            val design = app.getString("design")
            return StoreConfig(
                headline = StoreTextStyle.parse(text.getJSONObject("headline"), baseDir),
                caption = StoreTextStyle.parse(text.getJSONObject("caption"), baseDir),
                backgroundColors = background.getJSONArray("colors").strings().map(::parseColor),
                backgroundRings = background.optBoolean("rings", true),
                deviceFrameColor = parseColor(device.getString("frameColor")),
                statusBarTime = device.getString("statusBarTime"),
                appDesign = DesignStyle.entries.firstOrNull { it.name == design }
                    ?: error("app.design は ${DesignStyle.entries.joinToString()} のどれか(今: $design)"),
                appDarkTheme = app.optBoolean("dark", false),
                featureTitle = feature.getString("title"),
                featureTagline = feature.getString("tagline"),
                phone = root.getJSONArray("phone").objects().map(StoreShot::parse),
                tablet = root.getJSONArray("tablet").objects().map(StoreShot::parse),
                video = StoreVideoConfig(
                    headlineSizeSp = video.getDouble("headlineSize").toFloat(),
                    captionSizeSp = video.getDouble("captionSize").toFloat(),
                    title = video.getString("title"),
                    tagline = video.getString("tagline"),
                    scenes = video.getJSONObject("scenes").let { scenes ->
                        scenes.keys().asSequence().associateWith { key ->
                            val scene = scenes.getJSONObject(key)
                            StoreCaption(scene.getString("headline"), scene.getString("caption"))
                        }
                    }
                )
            )
        }
    }
}

/**
 * 見出し・説明の文字の見た目。
 * @property fontFile フォントのファイル(TTF・OTF。TTC は最初の書体を使う)。null なら既定の書体
 * @property weight 太さ(100〜900)。可変フォントなら、その太さで描く
 */
internal data class StoreTextStyle(val fontFile: File?, val weight: Int, val sizeSp: Float, val color: Color) {

    val fontWeight: FontWeight get() = FontWeight(weight)

    /**
     * 指定のフォントの書体。指定が無ければ null(既定の書体を使う)。
     * ファイルがあるかは、描くときに初めて確かめる(CI などフォントの無い環境でも、設定の読み込みだけは通るように)。
     */
    val fontFamily: FontFamily? by lazy {
        fontFile?.let { file ->
            require(file.isFile) { "フォントのファイルが見つかりません: $file(store-assets.json の text.*.font)" }
            FontFamily(
                Font(
                    file = file,
                    weight = fontWeight,
                    // 可変フォント(NotoSansJP-VF など)は、太さの軸をここで決める
                    variationSettings = FontVariation.Settings(FontVariation.weight(weight))
                )
            )
        }
    }

    companion object {
        /** `font` は絶対パスか、設定のファイルからの相対パス */
        fun parse(json: JSONObject, baseDir: File): StoreTextStyle {
            val path = if (json.isNull("font")) null else json.getString("font")
            val font = path?.let { p -> File(p).takeIf { it.isAbsolute } ?: File(baseDir, p) }
            return StoreTextStyle(
                fontFile = font,
                weight = json.optInt("weight", 400),
                sizeSp = json.getDouble("size").toFloat(),
                color = parseColor(json.getString("color"))
            )
        }
    }
}

/**
 * 1枚のスクリーンショット。
 * @property file 出力のファイル名(拡張子なし)。Play ではファイル名の順に並ぶ
 * @property screen 写す画面([StoreScreens.phoneScreens] / [StoreScreens.tabletScreens] の名前)
 * @property scrollDp 撮る前にスクロールする量(dp)。null なら画面ごとの既定
 *
 * パラメータ付きのテスト(public)のコンストラクタに渡すので public にしている。
 */
data class StoreShot(
    val file: String,
    val screen: String,
    val headline: String,
    val caption: String,
    val scrollDp: Float?
) {
    override fun toString(): String = file

    companion object {
        fun parse(json: JSONObject) = StoreShot(
            file = json.getString("file"),
            screen = json.getString("screen"),
            headline = json.optString("headline", ""),
            caption = json.optString("caption", ""),
            scrollDp = if (json.has("scroll")) json.getDouble("scroll").toFloat() else null
        )
    }
}

internal data class StoreCaption(val headline: String, val caption: String)

internal data class StoreVideoConfig(
    val headlineSizeSp: Float,
    val captionSizeSp: Float,
    val title: String,
    val tagline: String,
    /** 場面の名前(share・organize・detail・search・chat・graph・ai)→ 見出し */
    val scenes: Map<String, StoreCaption>
) {
    fun caption(scene: String): StoreCaption =
        scenes[scene] ?: error("video.scenes に「$scene」の見出しがありません")
}

/** "#RRGGBB" か "#AARRGGBB" */
internal fun parseColor(text: String): Color {
    val hex = text.removePrefix("#")
    require(hex.length == 6 || hex.length == 8) { "色は #RRGGBB か #AARRGGBB で書く(今: $text)" }
    val argb = hex.toLong(16).let { if (hex.length == 6) it or 0xFF000000L else it }
    return Color(argb.toInt())
}

private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
