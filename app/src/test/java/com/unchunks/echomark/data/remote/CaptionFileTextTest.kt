package com.unchunks.echomark.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionFileTextTest {

    @Test
    fun WebVTTの時刻と見出しとタグを除いて文章にする() {
        val vtt = """
            WEBVTT
            Kind: captions
            Language: ja

            NOTE これは注釈
            二行目の注釈

            1
            00:00:01.000 --> 00:00:03.000 align:start
            <v 話し手>こんにちは&amp;ようこそ

            2
            00:00:03.000 --> 00:00:05.000
            こんにちは&amp;ようこそ
            今日は<c.yellow>動画</c>の話です
        """.trimIndent()

        assertEquals("こんにちは&ようこそ\n今日は動画の話です", CaptionFileText.parse(vtt))
    }

    @Test
    fun SubRipも読める() {
        val srt = 0xFEFF.toChar() + "1\r\n00:00:01,000 --> 00:00:02,000\r\nはじめに\r\n\r\n2\r\n00:00:02,000 --> 00:00:04,000\r\n<i>まとめ</i>\r\n"

        assertEquals("はじめに\nまとめ", CaptionFileText.parse(srt))
    }

    @Test
    fun 文章が無ければ空() {
        assertEquals("", CaptionFileText.parse("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n\n"))
    }
}
