package com.unchunks.echomark.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryStyleTest {

    @Test
    fun 既定は日本語の文章_今までどおり() {
        val style = SummaryStyle()

        assertEquals(SummaryLanguage.JAPANESE, style.language)
        assertEquals(SummaryFormat.PARAGRAPH, style.format)
        assertEquals(150, style.maxChars(150))
        assertEquals("100文字以内の日本語", style.language.limitPhrase(100))
    }

    @Test
    fun 英語は日本語の文字数に当たる語数で上限を伝える() {
        assertEquals("60語以内の英語", SummaryLanguage.ENGLISH.limitPhrase(100))
        assertEquals("90語以内の英語", SummaryLanguage.ENGLISH.limitPhrase(150))
        assertEquals("120語以内の英語", SummaryLanguage.ENGLISH.limitPhrase(200))
        assertEquals("100文字以内の中国語(簡体字)", SummaryLanguage.CHINESE.limitPhrase(100))
        assertEquals("100文字以内の韓国語", SummaryLanguage.KOREAN.limitPhrase(100))
    }

    @Test
    fun 書き方に合わせて長さの上限を直す() {
        assertEquals(100, SummaryStyle(format = SummaryFormat.PARAGRAPH).maxChars(100))
        // 箇条書きは行の分だけ長く
        assertEquals(150, SummaryStyle(format = SummaryFormat.BULLETS).maxChars(100))
        assertEquals(300, SummaryStyle(format = SummaryFormat.BULLETS).maxChars(200))
        // 1 行は長い本文でも短く保つ。もともと短ければそのまま
        assertEquals(SummaryStyle.ONE_LINE_MAX_CHARS, SummaryStyle(format = SummaryFormat.ONE_LINE).maxChars(200))
        assertEquals(40, SummaryStyle(format = SummaryFormat.ONE_LINE).maxChars(40))
    }

    @Test
    fun 出力の長さの上限は言語に合わせて広げる() {
        assertEquals(150, SummaryStyle().schemaChars(150))
        assertEquals(600, SummaryStyle(language = SummaryLanguage.ENGLISH).schemaChars(150))
        assertEquals(150, SummaryStyle(language = SummaryLanguage.CHINESE).schemaChars(150))
    }

    @Test
    fun 設定画面で選べる言語と書き方は表示名を持つ() {
        assertEquals(listOf("日本語", "English", "中文(简体)", "한국어"), SummaryLanguage.entries.map { it.label })
        assertEquals(listOf("文章", "箇条書き", "1 行"), SummaryFormat.entries.map { it.label })
    }
}
