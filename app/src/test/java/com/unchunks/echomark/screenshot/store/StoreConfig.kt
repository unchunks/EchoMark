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
                headline = StoreTextStyle.parse(text.getJSONObject("headline"), baseDir),
                caption = StoreTextStyle.parse(text.getJSONObject("caption"), baseDir),
                backgroundColors = background.getJSONArray("colors").strings().map(::parseColor),
                backgroundRings = background.optBoolean("rings", true),
                deviceFrameColor = parseColor(device.getString("frameColor")),
                statusBarTime = device.getString("statusBarTime"),
                appDesign = DesignStyle.entries.firstOrNull { it.name == design }
                    ?: error("app.design は ${DesignStyle.entries.joinToString()} のどれか(今: $design)"),
                appDarkTheme = app.optBoolean("dark", false),
                appFont = StoreAppFont(
                    regular = fontFileOf(app, "font", baseDir),
                    bold = fontFileOf(app, "boldFont", baseDir)
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
        fun parse(json: JSONObject, baseDir: File): StoreTextStyle {
            return StoreTextStyle(
                fontFile = fontFileOf(json, "font", baseDir),
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
 * - 太さが1つだけのフォントは、[bold] があれば太字にそれを使い、無ければ太字を機械的に太らせる
 */
internal data class StoreAppFont(val regular: File?, val bold: File?) {

    val fontFamily: FontFamily? by lazy {
        val file = regular ?: return@lazy null
        require(file.isFile) { "フォントのファイルが見つかりません: $file(store-assets.json の app.font)" }
        val boldFile = bold?.also { require(it.isFile) { "フォントのファイルが見つかりません: $it(store-assets.json の app.boldFont)" } }
        val fonts = if (boldFile == null && isVariableFont(file)) {
            VARIABLE_WEIGHTS.map { w ->
                Font(file = file, weight = FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
            }
        } else {
            listOfNotNull(Font(file = file, weight = FontWeight.Normal), boldFile?.let { Font(file = it, weight = FontWeight.Bold) })
        }
        FontFamily(fonts)
    }

    private companion object {
        /** アプリの画面で使う太さ(Material 3 の文字の型は 400・500、太字は 600・700) */
        val VARIABLE_WEIGHTS = listOf(300, 400, 500, 600, 700, 800)
    }
}

/**
 * フォントのファイルに太さなどの軸(fvar の表)があるか。TTC は最初の書体を見る。
 * 太さが1つだけのフォントに太さごとの書体を作ると、太字が太くならないため、作り方を分ける。
 */
internal fun isVariableFont(file: File): Boolean = runCatching {
    val bytes = file.readBytes()
    fun u32(at: Int) = ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or
        ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)
    fun u16(at: Int) = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
    // TTC は 'ttcf' の後ろに、それぞれの書体の表の位置が並ぶ
    val start = if (String(bytes, 0, 4, Charsets.US_ASCII) == "ttcf") u32(12).toInt() else 0
    val tables = u16(start + 4)
    (0 until tables).any { i -> String(bytes, start + 12 + i * 16, 4, Charsets.US_ASCII) == "fvar" }
}.getOrDefault(false)

/** [key] のフォントのファイル。絶対パスか、設定のファイルからの相対パス。null なら指定なし */
private fun fontFileOf(json: JSONObject, key: String, baseDir: File): File? {
    if (!json.has(key) || json.isNull(key)) return null
    val path = json.getString(key)
    return File(path).takeIf { it.isAbsolute } ?: File(baseDir, path)
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
