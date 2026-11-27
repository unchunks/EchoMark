package com.unchunks.echomark.screenshot.store

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import java.io.File
import java.io.RandomAccessFile

/*
 * 設定(store-assets.json)の font・boldFont には、フォントのファイルのパスか、パソコンに入っているフォントの名前を書ける。
 * 名前は「Noto Sans JP」(ファミリー)・「Noto Sans JP Medium」(ファミリー + 太さ)・「BIZ UDGothic Bold」「NotoSansJP-Medium」
 * (書体の名前)のどれでもよく、大文字・小文字・空白・ハイフンの違いは見ない。
 */

/**
 * 設定に書いたフォント。探すのは描くときに初めて(CI などフォントの無い環境でも、設定の読み込みだけは通るように)。
 * @property key 見つからないときのメッセージに出す設定の項目(text.headline.font など)
 */
internal data class StoreFontSpec(val text: String, val baseDir: File, val key: String) {
    val face: StoreFontFace by lazy { StoreFontFace.resolve(text, baseDir, key) }
}

/**
 * 決まった書体。
 * @property weight 名前で太さまで決めたとき(「Noto Sans JP Medium」なら 500)。null なら使う所の太さで描く
 */
internal data class StoreFontFace(val file: File, val variable: Boolean, val weight: Int?) {

    /** [declaredWeight] の太さとして使う書体。可変フォントは、名前で決めた太さ(無ければ [declaredWeight])で描く */
    fun font(declaredWeight: Int): Font = Font(
        file = file,
        weight = FontWeight(declaredWeight),
        variationSettings = if (variable) {
            FontVariation.Settings(FontVariation.weight(weight ?: declaredWeight))
        } else {
            FontVariation.Settings()
        }
    )

    companion object {
        /** [spec] がファイル(絶対パスか、設定のファイルからの相対パス)ならそれを、そうでなければ入っているフォントの名前として探す */
        fun resolve(spec: String, baseDir: File, key: String): StoreFontFace {
            val file = File(spec).takeIf { it.isAbsolute } ?: File(baseDir, spec)
            if (file.isFile) return StoreFontFace(file, isVariableFont(file), weight = null)
            require(!looksLikePath(spec)) { "フォントのファイルが見つかりません: $file(store-assets.json の $key)" }

            val fonts = InstalledFonts.all
            val match = InstalledFonts.find(spec, fonts) ?: error(
                "「$spec」というフォントが見つかりません(store-assets.json の $key)。" +
                    "Windows の「設定 > 個人用設定 > フォント」に出る名前か、フォントのファイルのパスを書いてください。" +
                    InstalledFonts.similarFamilies(spec, fonts).takeIf { it.isNotEmpty() }
                        ?.joinToString(prefix = "似た名前: ") { "「$it」" }.orEmpty()
            )
            // Compose はフォントのファイルの最初の書体しか読めない(TTC の中から選べない)
            require(match.font.index == 0) {
                "「$spec」は ${match.font.file.name} の ${match.font.index + 1} 番目の書体のため使えません" +
                    "(store-assets.json の $key)。同じファイルの最初の書体「${match.font.families.firstOrNull()}」なら使えます"
            }
            return StoreFontFace(match.font.file, match.font.variable, match.weight)
        }

        private fun looksLikePath(spec: String): Boolean =
            '/' in spec || '\\' in spec || spec.substringAfterLast('.').lowercase() in InstalledFonts.EXTENSIONS
    }
}

/**
 * 入っているフォントの1つの書体(TTC は書体ごとに1つ)。
 * @property index ファイルの中の何番目の書体か(TTC 以外は 0)
 * @property families ファミリーの名前(名前の表の 1 と 16。言語ごと)
 * @property fullNames 書体の名前(名前の表の 4 と 6、ファミリー + スタイルの名前)
 * @property weight OS/2 の表の太さ(可変フォントは既定の太さ)
 */
internal data class InstalledFont(
    val file: File,
    val index: Int,
    val families: List<String>,
    val fullNames: List<String>,
    val weight: Int,
    val italic: Boolean,
    val variable: Boolean
)

/** 名前で見つけた書体と、名前で決まった太さ(null なら使う所の太さ) */
internal data class InstalledFontMatch(val font: InstalledFont, val weight: Int?)

internal object InstalledFonts {

    val EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")

    /** 入っているフォントのすべての書体。名前の表と OS/2 の表だけを読むので、数百のフォントでも速い */
    val all: List<InstalledFont> by lazy {
        fontDirs().flatMap { dir ->
            dir.walkTopDown().maxDepth(4)
                .filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
                .flatMap { readFaces(it) }
                .toList()
        }
    }

    /** Windows(全員用と自分用)・macOS・Linux のフォントの置き場所 */
    private fun fontDirs(): List<File> {
        val home = System.getProperty("user.home").orEmpty()
        return listOfNotNull(
            System.getenv("WINDIR")?.let { File(it, "Fonts") },
            System.getenv("LOCALAPPDATA")?.let { File(it, "Microsoft/Windows/Fonts") },
            File("/System/Library/Fonts"),
            File("/Library/Fonts"),
            File(home, "Library/Fonts"),
            File("/usr/share/fonts"),
            File("/usr/local/share/fonts"),
            File(home, ".local/share/fonts"),
            File(home, ".fonts")
        ).filter { it.isDirectory }
    }

    /**
     * [name] の書体を [fonts] から探す。
     * 1. ファミリーの名前(「Noto Sans JP」)… 太さは使う所に任せる。可変フォントがあればそれを選ぶ
     * 2. 書体の名前(「BIZ UDGothic Bold」)… その書体の太さで描く
     * 3. ファミリーの名前 + 太さ(「Noto Sans JP Medium」)… 可変フォントの太さは名前の表に書体として無いことが多いため
     *
     * 可変フォントは、名前の終わりに太さがあればその太さで描く。名前の表の太さは既定の太さ(NotoSansJP-VF は Thin)で、
     * 「Noto Sans JP Regular」「Noto Sans JP Thin」(古い形のファミリーの名前)のような名前とは合わないことがあるため。
     */
    fun find(name: String, fonts: List<InstalledFont>): InstalledFontMatch? {
        val key = normalize(name)
        if (key.isEmpty()) return null
        val suffix = weightSuffix(key)

        fonts.filter { font -> font.families.any { normalize(it) == key } }
            .takeIf { it.isNotEmpty() }
            ?.let { faces ->
                val face = best(faces, targetWeight = suffix?.second)
                return InstalledFontMatch(face, if (face.variable) suffix?.second else null)
            }

        fonts.filter { font -> font.fullNames.any { normalize(it) == key } }
            .takeIf { it.isNotEmpty() }
            ?.let { faces ->
                val face = best(faces, targetWeight = suffix?.second)
                return InstalledFontMatch(face, if (face.variable) suffix?.second ?: face.weight else face.weight)
            }

        val (familyKey, weight) = suffix ?: return null
        val family = fonts.filter { font -> font.families.any { normalize(it) == familyKey } }
        if (family.isEmpty()) return null
        val face = best(family, targetWeight = weight)
        return InstalledFontMatch(face, if (face.variable) weight else face.weight)
    }

    /** 名前の終わりの太さ(「notosansjpmedium」→「notosansjp」と 500)。無ければ null */
    private fun weightSuffix(key: String): Pair<String, Int>? = WEIGHT_NAMES.firstNotNullOfOrNull { (word, w) ->
        if (key.endsWith(word) && key.length > word.length) key.removeSuffix(word) to w else null
    }

    /** 見つからなかったときに出す、似たファミリーの名前(最初の語が同じもの) */
    fun similarFamilies(name: String, fonts: List<InstalledFont>): List<String> {
        val firstWord = normalize(name.trim().split(' ', '-', '_').first())
        if (firstWord.isEmpty()) return emptyList()
        return fonts.flatMap { it.families }.distinct().filter { normalize(it).startsWith(firstWord) }.sorted().take(8)
    }

    /** 斜体でないもの → 可変フォント → 太さが [targetWeight](無ければ標準)に近いもの → ファイルの最初の書体、の順に選ぶ */
    private fun best(faces: List<InstalledFont>, targetWeight: Int?): InstalledFont = faces.minWith(
        compareBy<InstalledFont> { it.italic }
            .thenBy { !it.variable }
            .thenBy { kotlin.math.abs(it.weight - (targetWeight ?: 400)) }
            .thenBy { it.index }
    )

    /** 大文字・小文字・空白・ハイフンなどの違いを見ない */
    private fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    /** 名前の終わりの太さ(長いものから比べる。「extrabold」を「bold」と取り違えないように) */
    private val WEIGHT_NAMES: List<Pair<String, Int>> = listOf(
        "thin" to 100, "hairline" to 100,
        "extralight" to 200, "ultralight" to 200,
        "light" to 300,
        "regular" to 400, "normal" to 400, "book" to 400,
        "medium" to 500,
        "semibold" to 600, "demibold" to 600,
        "bold" to 700,
        "extrabold" to 800, "ultrabold" to 800,
        "black" to 900, "heavy" to 900
    ).sortedByDescending { it.first.length }

    /** フォントのファイルの書体を読む。読めないファイルは無視する */
    fun readFaces(file: File): List<InstalledFont> = runCatching {
        RandomAccessFile(file, "r").use { font ->
            faceOffsets(font).mapIndexedNotNull { index, start -> readFace(font, file, index, start) }
        }
    }.getOrDefault(emptyList())

    private fun readFace(font: RandomAccessFile, file: File, index: Int, start: Long): InstalledFont? {
        val tables = readTables(font, start)
        val (nameOffset, nameLength) = tables["name"] ?: return null
        val names = readNames(font.bytes(nameOffset, nameLength.toInt()))
        val os2 = tables["OS/2"]
        return InstalledFont(
            file = file,
            index = index,
            families = names.families,
            fullNames = names.fullNames,
            weight = os2?.let { font.u16(it.first + 4) } ?: 400,
            // fsSelection の 0 ビット目が斜体
            italic = os2 != null && os2.second >= 64 && (font.u16(os2.first + 62) and 1) != 0,
            variable = "fvar" in tables
        )
    }

    private class Names(val families: List<String>, val fullNames: List<String>)

    /** 名前の表から、ファミリーの名前と書体の名前を読む(言語ごと。日本語の名前でも探せるように) */
    private fun readNames(table: ByteArray): Names {
        fun u16(at: Int) = ((table[at].toInt() and 0xFF) shl 8) or (table[at + 1].toInt() and 0xFF)
        val count = u16(2)
        val stringOffset = u16(4)
        // (プラットフォーム, 文字コード, 言語) → 名前の番号 → 文字列
        val byLanguage = mutableMapOf<Triple<Int, Int, Int>, MutableMap<Int, String>>()
        for (i in 0 until count) {
            val record = 6 + i * 12
            val platform = u16(record)
            val encoding = u16(record + 2)
            val nameId = u16(record + 6)
            if (nameId !in NAME_IDS) continue
            val charset = when {
                platform == 0 || platform == 3 -> Charsets.UTF_16BE
                platform == 1 && encoding == 0 -> Charsets.ISO_8859_1
                else -> continue
            }
            val length = u16(record + 8)
            val at = stringOffset + u16(record + 10)
            if (at + length > table.size) continue
            val text = String(table, at, length, charset).trim()
            if (text.isNotEmpty()) byLanguage.getOrPut(Triple(platform, encoding, u16(record + 4))) { mutableMapOf() }[nameId] = text
        }
        val families = mutableListOf<String>()
        val fullNames = mutableListOf<String>()
        for (names in byLanguage.values) {
            names[1]?.let(families::add)
            names[16]?.let(families::add)
            names[4]?.let(fullNames::add)
            names[6]?.let(fullNames::add)
            if (names[1] != null && names[2] != null) fullNames += "${names[1]} ${names[2]}"
            if (names[16] != null && names[17] != null) fullNames += "${names[16]} ${names[17]}"
        }
        return Names(families.distinct(), fullNames.distinct())
    }

    /** 1 ファミリー・2 スタイル・4 書体・6 PostScript 名・16 / 17 ファミリーとスタイル(書体が多いフォント用) */
    private val NAME_IDS = setOf(1, 2, 4, 6, 16, 17)
}

/** 太さなどの軸(fvar の表)があるか。TTC は最初の書体を見る */
internal fun isVariableFont(file: File): Boolean = runCatching {
    RandomAccessFile(file, "r").use { font -> "fvar" in readTables(font, faceOffsets(font).first()) }
}.getOrDefault(false)

/** 書体の表の場所。TTC は 'ttcf' の後ろに、それぞれの書体の表の位置が並ぶ */
private fun faceOffsets(font: RandomAccessFile): List<Long> =
    if (font.tag(0) == "ttcf") (0 until font.u32(8).toInt()).map { font.u32(12L + it * 4) } else listOf(0L)

/** 表の名前 → (位置, 長さ) */
private fun readTables(font: RandomAccessFile, start: Long): Map<String, Pair<Long, Long>> =
    (0 until font.u16(start + 4)).associate { i ->
        val record = start + 12 + i * 16L
        font.tag(record) to (font.u32(record + 8) to font.u32(record + 12))
    }

private fun RandomAccessFile.u16(at: Long): Int {
    seek(at)
    return readUnsignedShort()
}

private fun RandomAccessFile.u32(at: Long): Long {
    seek(at)
    return readInt().toLong() and 0xFFFFFFFFL
}

private fun RandomAccessFile.tag(at: Long): String = String(bytes(at, 4), Charsets.ISO_8859_1)

private fun RandomAccessFile.bytes(at: Long, length: Int): ByteArray {
    seek(at)
    return ByteArray(length).also { readFully(it) }
}
