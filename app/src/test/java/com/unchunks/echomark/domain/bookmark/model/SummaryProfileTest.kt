package com.unchunks.echomark.domain.bookmark.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 要約の型の判定(URL のホスト・パスと本文から)。 */
class SummaryProfileTest {

    private fun web(url: String, text: String = "", title: String = "") =
        summaryProfileOf(ContentKind.WEB_PAGE, url, title, text)

    @Test
    fun 学術サイトのURLは論文() {
        assertEquals(SummaryProfile.PAPER, web("https://arxiv.org/abs/2401.00001"))
        assertEquals(SummaryProfile.PAPER, web("https://www.nature.com/articles/s41586-024-00000-0"))
        assertEquals(SummaryProfile.PAPER, web("https://link.springer.com/article/10.1007/s00000"))
        assertEquals(SummaryProfile.PAPER, web("https://pubmed.ncbi.nlm.nih.gov/12345678/"))
        // nature.com の記事以外は論文にしない
        assertEquals(SummaryProfile.GENERAL, web("https://www.nature.com/news/"))
    }

    @Test
    fun 技術資料のURLはリファレンス() {
        assertEquals(SummaryProfile.REFERENCE, web("https://developer.android.com/topic/architecture"))
        assertEquals(SummaryProfile.REFERENCE, web("https://docs.python.org/3/library/json.html"))
        assertEquals(SummaryProfile.REFERENCE, web("https://kotlinlang.org/docs/coroutines-guide.html"))
        assertEquals(SummaryProfile.REFERENCE, web("https://example.com/docs/getting-started"))
        // Kotlin のブログはリファレンスではない
        assertEquals(SummaryProfile.GENERAL, web("https://kotlinlang.org/blog/2026/"))
    }

    @Test
    fun 質問サイトとSNSと商品とレシピ() {
        assertEquals(SummaryProfile.QA, web("https://stackoverflow.com/questions/1/how-to"))
        assertEquals(SummaryProfile.QA, web("https://detail.chiebukuro.yahoo.co.jp/qa/question_detail/q1"))
        assertEquals(SummaryProfile.SOCIAL_POST, web("https://x.com/someone/status/1"))
        assertEquals(SummaryProfile.SOCIAL_POST, web("https://bsky.app/profile/a/post/b"))
        assertEquals(SummaryProfile.PRODUCT, web("https://www.amazon.co.jp/dp/B000000000"))
        // 商品ページ以外の Amazon は商品にしない
        assertEquals(SummaryProfile.GENERAL, web("https://www.amazon.co.jp/gp/help/customer/display.html"))
        assertEquals(SummaryProfile.RECIPE, web("https://cookpad.com/recipe/1"))
    }

    @Test
    fun URLに手がかりが無ければ本文の特徴で決める() {
        assertEquals(
            SummaryProfile.RECIPE,
            web("https://blog.example.com/teriyaki", text = "照り焼きチキン\n材料(2人分)\n鶏もも肉 1枚\n作り方\n1. 焼く")
        )
        // 材料だけで作り方が無ければレシピにしない
        assertEquals(SummaryProfile.GENERAL, web("https://blog.example.com/a", text = "材料費が高騰している"))
    }

    @Test
    fun PDFは論文のようなAbstractと参考文献があれば論文() {
        val paper = "Title\nAbstract\nWe propose a method.\n1 Introduction\n...\nReferences\n[1] A. B."
        assertEquals(SummaryProfile.PAPER, summaryProfileOf(ContentKind.DOCUMENT, null, "paper.pdf", paper))
        assertEquals(SummaryProfile.GENERAL, summaryProfileOf(ContentKind.DOCUMENT, null, "invoice.pdf", "請求書\n合計 1,200円"))
    }

    @Test
    fun 箇条書きだらけのメモは項目を並べたメモ() {
        assertEquals(
            SummaryProfile.LIST,
            summaryProfileOf(ContentKind.MEMO, null, "買い物", "- 牛乳\n- 卵\n・パン\n1. 米")
        )
        assertEquals(
            SummaryProfile.LIST,
            summaryProfileOf(ContentKind.MEMO, null, "ToDo", "[ ] 洗濯\n[x] 掃除\n[ ] 返信")
        )
        // 文章のメモは型なし。箇条書きが少ないときも
        assertEquals(SummaryProfile.GENERAL, summaryProfileOf(ContentKind.MEMO, null, "メモ", "今日は会議があった。\n結論は来週に持ち越し。\n- 宿題あり"))
        assertEquals(SummaryProfile.GENERAL, summaryProfileOf(ContentKind.MEMO, null, "メモ", "- 牛乳\n- 卵"))
    }

    @Test
    fun 種類に合わない型にはならない() {
        // 動画・画像・音声は型を持たない(arXiv の URL でも動画ならそのまま)
        assertEquals(SummaryProfile.GENERAL, summaryProfileOf(ContentKind.VIDEO, "https://arxiv.org/abs/1", "", ""))
        assertEquals(SummaryProfile.GENERAL, summaryProfileOf(ContentKind.IMAGE, null, "", "- a\n- b\n- c"))
        SummaryProfile.entries.forEach { profile ->
            assertTrue(profile.name, profile.kinds.isNotEmpty())
        }
    }

    @Test
    fun URLでないものと壊れたURLは型なし() {
        assertEquals(SummaryProfile.GENERAL, web("not a url"))
        assertEquals(SummaryProfile.GENERAL, summaryProfileOf(ContentKind.WEB_PAGE, null, "", ""))
        assertEquals(SummaryProfile.GENERAL, web("https://"))
    }
}
