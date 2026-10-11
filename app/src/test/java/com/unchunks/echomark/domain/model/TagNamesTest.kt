package com.unchunks.echomark.domain.model

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
}
