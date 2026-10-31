package com.unchunks.echomark.domain.model

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** AI が付けるタグ名の表記ゆれの吸収。 */
class TagNamesTest {

    @Test
    fun 大文字小文字と全角半角の違いは既存のタグにそろえる() {
        assertEquals(
            listOf("Android", "Kotlin"),
            TagNames.resolve(listOf("android", "ｋｏｔｌｉｎ"), existing = listOf("Android", "Kotlin"))
        )
    }

    @Test
    fun 一致しないタグは整えた名前で新しく作る() {
        assertEquals(
            listOf("Compose", "読書"),
            TagNames.resolve(listOf(" #Compose ", "＃読書"), existing = listOf("Android"))
        )
    }

    @Test
    fun 空になったものと表記ゆれの重複は除く() {
        assertEquals(
            listOf("Android"),
            TagNames.resolve(listOf("ANDROID", "#", "  ", "android"), existing = listOf("Android"))
        )
    }

    @Test
    fun 同じキーの既存タグが複数あれば先にあるものを使う() {
        assertEquals(
            listOf("android"),
            TagNames.resolve(listOf("ANDROID"), existing = listOf("android", "Android"))
        )
    }


    @Test
    fun 表記ゆれの候補のキーは区切りとかなと語尾の長音を無視する() {
        assertEquals(TagNames.variantKey("Machine Learning"), TagNames.variantKey("machine-learning"))
        assertEquals(TagNames.variantKey("サーバー"), TagNames.variantKey("サーバ"))
        assertEquals(TagNames.variantKey("ネコ"), TagNames.variantKey("ねこ"))
        assertEquals(TagNames.variantKey("ｱﾝﾄﾞﾛｲﾄﾞ"), TagNames.variantKey("あんどろいど"))
        // 語中の長音は残す
        assertNotEquals(TagNames.variantKey("ラーメン"), TagNames.variantKey("ラメン"))
        assertNotEquals(TagNames.variantKey("C"), TagNames.variantKey("C++"))
    }

    @Test
    fun 同じタグは完全一致を優先し_無ければ表記ゆれの範囲で探す() {
        val existing = listOf("Android", "android", "Kotlin")

        assertEquals("android", TagNames.findSame("android", existing))
        assertEquals("Kotlin", TagNames.findSame("ＫＯＴＬＩＮ", existing))
        assertEquals(null, TagNames.findSame("Compose", existing))
    }


    @Test
    fun 別名は既存のタグが無いときだけ今のタグ名にそろえる() {
        val aliases = mapOf("ネコ" to "猫", "kotlin" to "Kotlin言語")

        assertEquals(listOf("猫"), TagNames.resolve(listOf("ネコ"), existing = listOf("猫"), aliases = aliases))
        // 全角/半角・大文字/小文字を区別しない
        assertEquals(listOf("Kotlin言語"), TagNames.resolve(listOf("ＫＯＴＬＩＮ"), existing = listOf("Android"), aliases = aliases))
        // 同じキーの既存のタグがあれば、別名より優先する
        assertEquals(listOf("kotlin"), TagNames.resolve(listOf("Kotlin"), existing = listOf("kotlin"), aliases = aliases))
    }

    @Test
    fun 区切り_かな_語尾の長音の違いも既存のタグにそろえる() {
        assertEquals(
            listOf("サーバー", "machine learning", "ねこ"),
            TagNames.resolve(
                listOf("サーバ", "machine-learning", "ネコ"),
                existing = listOf("サーバー", "machine learning", "ねこ")
            )
        )
    }

    @Test
    fun 別名や表記ゆれで同じタグになったものは重複を除く() {
        assertEquals(
            listOf("猫"),
            TagNames.resolve(listOf("ネコ", "猫", "ねこ"), existing = listOf("猫"), aliases = mapOf("ネコ" to "猫", "ねこ" to "猫"))
        )
    }
}
