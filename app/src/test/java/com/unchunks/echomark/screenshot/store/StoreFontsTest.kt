package com.unchunks.echomark.screenshot.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/** ストアの画像の設定で、フォントを名前で探す所([InstalledFonts])。入っているフォントに頼らないよう、書体の一覧は作って渡す */
class StoreFontsTest {

    @get:Rule
    val temp = TemporaryFolder()

    // Windows の NotoSansJP-VF.ttf と同じ名前の表(既定の太さは Thin)
    private val notoVariable = face(
        "NotoSansJP-VF.ttf",
        families = listOf("Noto Sans JP"),
        fullNames = listOf("Noto Sans JP", "NotoSansJP-Thin", "Noto Sans JP Regular"),
        weight = 100,
        variable = true
    )

    // Google Fonts の NotoSansJP-VariableFont_wght.ttf。古い形のファミリーの名前に太さが付いている
    private val notoVariableThin = face(
        "NotoSansJP-VariableFont_wght.ttf",
        families = listOf("Noto Sans JP Thin", "Noto Sans JP"),
        fullNames = listOf("Noto Sans JP Thin", "NotoSansJP-Thin"),
        weight = 100,
        variable = true
    )
    private val bizRegular = face("BIZ-UDGothicR.ttc", families = listOf("BIZ UDGothic", "BIZ UDゴシック"), fullNames = listOf("BIZ UDGothic", "BIZUDGothic-Regular"))
    private val bizBold = face("BIZ-UDGothicB.ttc", families = listOf("BIZ UDGothic"), fullNames = listOf("BIZ UDGothic Bold", "BIZUDGothic-Bold"), weight = 700)
    private val bizBoldP = face("BIZ-UDGothicB.ttc", index = 1, families = listOf("BIZ UDPGothic"), fullNames = listOf("BIZ UDPGothic Bold"), weight = 700)
    private val fooItalic = face("Foo-Italic.ttf", families = listOf("Foo"), fullNames = listOf("Foo Italic"), italic = true)
    private val fooRegular = face("Foo-Regular.ttf", families = listOf("Foo"), fullNames = listOf("Foo Regular"))
    private val fooBold = face("Foo-Bold.ttf", families = listOf("Foo"), fullNames = listOf("Foo Bold"), weight = 700)
    private val fooExtraBold = face("Foo-ExtraBold.ttf", families = listOf("Foo"), fullNames = listOf("Foo ExtraBold"), weight = 800)
    private val fonts = listOf(notoVariable, notoVariableThin, bizRegular, bizBold, bizBoldP, fooItalic, fooRegular, fooBold, fooExtraBold)

    @Test
    fun ファミリーの名前なら_太さは使う所に任せる() {
        assertEquals(InstalledFontMatch(notoVariable, weight = null), InstalledFonts.find("Noto Sans JP", fonts))
        // 太さが1つのフォントは、斜体でない標準の太さの書体
        assertEquals(InstalledFontMatch(fooRegular, weight = null), InstalledFonts.find("Foo", fonts))
        assertEquals(InstalledFontMatch(bizRegular, weight = null), InstalledFonts.find("BIZ UDGothic", fonts))
    }

    @Test
    fun 大文字小文字や空白やハイフンの違いは見ない() {
        assertEquals(notoVariable, InstalledFonts.find("notosansjp", fonts)?.font)
        assertEquals(InstalledFontMatch(bizBold, weight = 700), InstalledFonts.find("BIZUDGothic-Bold", fonts))
    }

    @Test
    fun 日本語の名前でも探せる() {
        assertEquals(bizRegular, InstalledFonts.find("BIZ UDゴシック", fonts)?.font)
    }

    @Test
    fun 書体の名前なら_その書体の太さで描く() {
        assertEquals(InstalledFontMatch(bizBold, weight = 700), InstalledFonts.find("BIZ UDGothic Bold", fonts))
        assertEquals(InstalledFontMatch(bizBoldP, weight = 700), InstalledFonts.find("BIZ UDPGothic Bold", fonts))
    }

    @Test
    fun 可変フォントはファミリーの名前と太さで_その太さを使う() {
        assertEquals(InstalledFontMatch(notoVariable, weight = 500), InstalledFonts.find("Noto Sans JP Medium", fonts))
        assertEquals(InstalledFontMatch(notoVariable, weight = 600), InstalledFonts.find("NotoSansJP-SemiBold", fonts))
        // 「extrabold」を「bold」と取り違えない
        assertEquals(InstalledFontMatch(notoVariable, weight = 800), InstalledFonts.find("Noto Sans JP ExtraBold", fonts))
    }

    @Test
    fun 可変フォントは名前の表の既定の太さより_名前の太さを使う() {
        // 書体の名前(ファミリー + スタイル)に合っても、既定の太さ(Thin)ではなく Regular で描く
        assertEquals(InstalledFontMatch(notoVariable, weight = 400), InstalledFonts.find("Noto Sans JP Regular", fonts))
        // 「NotoSansJP-Thin」は古い形のファミリーの名前「Noto Sans JP Thin」と同じに見なすので、そちらが選ばれる
        assertEquals(InstalledFontMatch(notoVariableThin, weight = 100), InstalledFonts.find("NotoSansJP-Thin", fonts))
        // 古い形のファミリーの名前(「Noto Sans JP Thin」)に合っても、名前どおり Thin で描く
        assertEquals(InstalledFontMatch(notoVariableThin, weight = 100), InstalledFonts.find("Noto Sans JP Thin", fonts))
    }

    @Test
    fun 太さが1つのフォントはファミリーの名前と太さで_いちばん近い太さの書体を選ぶ() {
        // 書体の名前に無い言い方(Black)でも、近い太さの書体を選ぶ
        assertEquals(InstalledFontMatch(fooExtraBold, weight = 800), InstalledFonts.find("Foo Black", fonts))
        assertEquals(InstalledFontMatch(fooRegular, weight = 400), InstalledFonts.find("Foo Light", fonts))
    }

    @Test
    fun 入っていない名前は見つからない() {
        assertNull(InstalledFonts.find("Hoge Sans", fonts))
        assertNull(InstalledFonts.find("Bold", fonts))
        assertNull(InstalledFonts.find(" ", fonts))
        assertEquals(listOf("BIZ UDGothic", "BIZ UDPGothic", "BIZ UDゴシック"), InstalledFonts.similarFamilies("BIZ Mincho", fonts))
    }

    @Test
    fun フォントのファイルから名前と太さと斜体と可変かを読む() {
        val file = temp.newFile("TestSans-MediumItalic.ttf")
        writeFont(
            file,
            names = mapOf(1 to "Test Sans Medium", 2 to "Italic", 4 to "Test Sans Medium Italic", 16 to "Test Sans", 17 to "Medium Italic"),
            weight = 500,
            italic = true,
            variable = false
        )
        val face = InstalledFonts.readFaces(file).single()
        assertEquals(listOf("Test Sans Medium", "Test Sans"), face.families)
        assertTrue("Test Sans Medium Italic" in face.fullNames)
        assertEquals(500, face.weight)
        assertTrue(face.italic)
        assertFalse(face.variable)
        assertFalse(isVariableFont(file))

        val variable = temp.newFile("TestSans-VF.ttf")
        writeFont(variable, names = mapOf(1 to "Test Sans", 2 to "Regular"), weight = 400, italic = false, variable = true)
        assertTrue(InstalledFonts.readFaces(variable).single().variable)
        assertTrue(isVariableFont(variable))
    }

    @Test
    fun フォントでないファイルは無視する() {
        val file = temp.newFile("broken.ttf").apply { writeText("not a font") }
        assertEquals(emptyList<InstalledFont>(), InstalledFonts.readFaces(file))
        assertFalse(isVariableFont(file))
    }

    private fun face(
        fileName: String,
        index: Int = 0,
        families: List<String>,
        fullNames: List<String>,
        weight: Int = 400,
        italic: Boolean = false,
        variable: Boolean = false
    ) = InstalledFont(File(fileName), index, families, fullNames, weight, italic, variable)

    /** 名前の表・OS/2 の表(と可変なら fvar の表)だけの、最小のフォントのファイルを書く */
    private fun writeFont(file: File, names: Map<Int, String>, weight: Int, italic: Boolean, variable: Boolean) {
        val nameTable = ByteArrayOutputStream().also { bytes ->
            val strings = names.values.map { it.toByteArray(Charsets.UTF_16BE) }
            DataOutputStream(bytes).apply {
                writeShort(0)
                writeShort(names.size)
                writeShort(6 + names.size * 12)
                var offset = 0
                names.keys.zip(strings).forEach { (id, text) ->
                    // Windows(3)・Unicode(1)・英語(0x409)
                    writeShort(3); writeShort(1); writeShort(0x409); writeShort(id); writeShort(text.size); writeShort(offset)
                    offset += text.size
                }
                strings.forEach { write(it) }
            }
        }.toByteArray()
        val os2Table = ByteArray(96).also {
            it[4] = (weight shr 8).toByte()
            it[5] = weight.toByte()
            if (italic) it[63] = 1
        }
        val tables = listOfNotNull("OS/2" to os2Table, "name" to nameTable, if (variable) "fvar" to ByteArray(16) else null)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(0x00010000)
            writeShort(tables.size)
            writeShort(0); writeShort(0); writeShort(0)
            var offset = 12 + tables.size * 16
            tables.forEach { (tag, data) ->
                writeBytes(tag); writeInt(0); writeInt(offset); writeInt(data.size)
                offset += data.size
            }
            tables.forEach { (_, data) -> write(data) }
        }
        file.writeBytes(out.toByteArray())
    }
}
