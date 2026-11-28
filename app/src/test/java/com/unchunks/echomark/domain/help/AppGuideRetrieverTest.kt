package com.unchunks.echomark.domain.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGuideRetrieverTest {

    private fun idsFor(question: String) = AppGuideRetriever.retrieve(question).map { it.id }

    @Test
    fun 保存の仕方を聞くと保存の節が先頭に来る() {
        assertEquals("save-share", idsFor("ブラウザの記事を保存するには?").first())
    }

    @Test
    fun 言い換えでもキーワードで見つかる() {
        assertTrue("backup" in idsFor("機種変更のとき、データはどうすればいい?"))
        assertTrue("privacy" in idsFor("指紋でロックしたい"))
    }

    @Test
    fun 要約が作られないときは失敗の節を選ぶ() {
        assertEquals("ai-failed", idsFor("要約が作られない").first())
    }

    @Test
    fun 英字は大文字小文字と記号の違いを無視する() {
        assertTrue("processing" in idsFor("WIFI じゃないと処理されない?"))
    }

    @Test
    fun 渡す節の数は上限まで() {
        assertTrue(AppGuideRetriever.retrieve("設定", limit = 2).size <= 2)
    }

    @Test
    fun どれにも当たらなければ概要の節だけを返す() {
        assertEquals(listOf("overview"), idsFor("???"))
        assertEquals(listOf("overview"), idsFor(""))
    }

    @Test
    fun AIに渡す形は番号と題名と本文() {
        val section = GuideSection(id = "a", title = "題名", body = "本文")
        assertEquals(listOf("[1] 題名: 本文"), AppGuideRetriever.format(listOf(section)))
    }

    @Test
    fun 節のidは重ならない() {
        val ids = AppGuide.sections.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
