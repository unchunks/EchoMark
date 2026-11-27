package com.unchunks.echomark.screenshot.store

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
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
    /** 枠の中のアプリの画面の書体(設定の app.font・app.boldFont)。実際のアプリは端末の標準の書体のまま */
    val appFont: StoreAppFont,
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
                headline = StoreTextStyle.parse(text.getJSONObject("headline"), baseDir, "text.headline"),
                caption = StoreTextStyle.parse(text.getJSONObject("caption"), baseDir, "text.caption"),
                backgroundColors = background.getJSONArray("colors").strings().map(::parseColor),
                backgroundRings = background.optBoolean("rings", true),
                deviceFrameColor = parseColor(device.getString("frameColor")),
                statusBarTime = device.getString("statusBarTime"),
                appDesign = DesignStyle.entries.firstOrNull { it.name == design }
                    ?: error("app.design は ${DesignStyle.entries.joinToString()} のどれか(今: $design)"),
                appDarkTheme = app.optBoolean("dark", false),
                appFont = StoreAppFont(
                    regular = fontSpecOf(app, "font", baseDir, "app.font"),
                    bold = fontSpecOf(app, "boldFont", baseDir, "app.boldFont")
                ),
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
 * @property font フォント(ファイルか、入っているフォントの名前。[StoreFontSpec])。null なら既定の書体
 * @property weight 太さ(100〜900)。可変フォントなら、その太さで描く(フォントの名前で太さを決めたときは、そちら)
 */
internal data class StoreTextStyle(val font: StoreFontSpec?, val weight: Int, val sizeSp: Float, val color: Color) {

    val fontWeight: FontWeight get() = FontWeight(weight)

    /** 指定のフォントの書体。指定が無ければ null(既定の書体を使う) */
    val fontFamily: FontFamily? by lazy { font?.face?.let { FontFamily(it.font(weight)) } }

    companion object {
        fun parse(json: JSONObject, baseDir: File, key: String): StoreTextStyle {
            return StoreTextStyle(
                font = fontSpecOf(json, "font", baseDir, "$key.font"),
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

/**
 * 枠の中のアプリの画面の書体。[regular] が null なら変えない(アプリと同じ、端末の標準の書体)。
 *
 * - 可変フォント(NotoSansJP-VF など)は、1つのファイルから太さごとの書体を作る
 * - 太字(600 以上)には [bold] があればそれを使う。「Noto Sans JP Medium」のように名前で太さを決めれば、
 *   可変フォントでも太字をその太さで描ける
 * - 太さが1つだけのフォントで [bold] が無ければ、太字を機械的に太らせる
 *   (太さが1つのフォントに太さごとの書体を作ると、太字が太くならないため、標準の太さだけを宣言する)
 */
internal data class StoreAppFont(val regular: StoreFontSpec?, val bold: StoreFontSpec?) {

    val fontFamily: FontFamily? by lazy {
        val regularFace = regular?.face ?: return@lazy null
        val boldFace = bold?.face
        val fonts = WEIGHTS.mapNotNull { w ->
            val isBold = w >= BOLD_FROM
            // 標準の書体の名前で決めた太さ(「Noto Sans JP Light」など)は、太字には当てない
            val face = if (isBold) boldFace ?: regularFace.copy(weight = null) else regularFace
            when {
                face.variable -> face.font(w)
                !isBold && w == 400 -> face.font(w)
                isBold && w == 700 && boldFace != null -> face.font(w)
                else -> null
            }
        }
        FontFamily(fonts)
    }

    private companion object {
        /** アプリの画面で使う太さ(Material 3 の文字の型は 400・500、太字は 600・700) */
        val WEIGHTS = listOf(300, 400, 500, 600, 700, 800)
        const val BOLD_FROM = 600
    }
}

/** [key] のフォント(ファイルのパスか、入っているフォントの名前)。null か空なら指定なし */
private fun fontSpecOf(json: JSONObject, key: String, baseDir: File, path: String): StoreFontSpec? {
    if (!json.has(key) || json.isNull(key)) return null
    return json.getString(key).trim().takeIf { it.isNotEmpty() }?.let { StoreFontSpec(it, baseDir, path) }
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
