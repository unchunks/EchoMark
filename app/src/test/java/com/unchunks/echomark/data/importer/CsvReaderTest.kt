package com.unchunks.echomark.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvReaderTest {

    @Test
    fun 引用符の欄_欄の中のカンマと改行と二重の引用符を読む() {
        val rows = readCsv("a,\"b,c\",\"d\ne\",\"f\"\"g\"\n1,2,3,4\n")

        assertEquals(listOf(listOf("a", "b,c", "d\ne", "f\"g"), listOf("1", "2", "3", "4")), rows)
    }

    @Test
    fun CRLFとCRだけの改行_最後の改行が無い行も読む() {
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), readCsv("a,b\r\nc,d"))
        assertEquals(listOf(listOf("a"), listOf("b")), readCsv("a\rb\r"))
    }

    @Test
    fun 空行は読み飛ばし_空の欄は空文字で残す() {
        assertEquals(listOf(listOf("a", "", "c")), readCsv("\n\na,,c\n\n"))
        assertEquals(listOf(listOf("", "")), readCsv(","))
    }

    @Test
    fun 先頭のBOMを除く() {
        assertEquals(listOf(listOf("url", "title")), readCsv("﻿url,title\n"))
    }

    @Test
    fun タブ区切りを読む() {
        assertEquals(listOf(listOf("a", "b c", "d")), readCsv("a\tb c\td\n", delimiter = '\t'))
    }

    @Test
    fun 引用符が閉じていなくても最後まで読んで止まらない() {
        val rows = readCsv("a,\"b\nc,d")

        assertEquals(1, rows.size)
        assertEquals("a", rows[0][0])
        assertTrue(rows[0][1].startsWith("b"))
    }

    @Test
    fun 欄の途中の引用符はそのまま残す() {
        assertEquals(listOf(listOf("5\" pipe", "x")), readCsv("5\" pipe,x\n"))
    }
}
