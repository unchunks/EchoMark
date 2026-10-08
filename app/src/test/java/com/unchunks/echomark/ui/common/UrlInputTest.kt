package com.unchunks.echomark.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlInputTest {

    @Test
    fun スキーム付きのURLはそのまま返す() {
        assertEquals("https://example.com/a?b=1", normalizeUrlInput("  https://example.com/a?b=1 "))
        assertEquals("http://example.com", normalizeUrlInput("http://example.com"))
    }

    @Test
    fun スキームが無ければhttpsを補う() {
        assertEquals("https://example.com/path", normalizeUrlInput("example.com/path"))
        assertEquals("https://www.example.co.jp", normalizeUrlInput("www.example.co.jp"))
    }

    @Test
    fun URLでないものはnull() {
        assertNull(normalizeUrlInput(""))
        assertNull(normalizeUrlInput("hello"))
        assertNull(normalizeUrlInput("ただのメモ"))
        assertNull(normalizeUrlInput("ftp://example.com"))
        assertNull(normalizeUrlInput("https://example.com/a b"))
        assertNull(normalizeUrlInput("https://"))
    }

    @Test
    fun 文章からURLを取り出し末尾の句読点を除く() {
        assertEquals("https://example.com/x", findUrlInText("見て https://example.com/x。"))
        assertEquals("https://example.com/y", findUrlInText("(https://example.com/y)"))
        assertNull(findUrlInText("URL なし"))
    }
}
