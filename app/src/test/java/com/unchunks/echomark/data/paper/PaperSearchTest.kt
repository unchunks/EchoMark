package com.unchunks.echomark.data.paper

import com.unchunks.components.feed.FeedItem
import com.unchunks.echomark.domain.subscription.PaperSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** 論文のキーワード検索の URL の作り方と、検索の結果の読み方。 */
class PaperSearchTest {

    @Test
    fun キーワードは空白で区切り_引用符で囲んだ語句はそのまま() {
        val keywords = PaperSearch.parseKeywords("  \"Large Language Model\"   agent  ")!!

        assertEquals(listOf("large language model", "agent"), keywords.terms)
        assertEquals("\"Large Language Model\" agent", keywords.display)
    }

    @Test
    fun 検索式の記号と演算子は語から除く_同じ語は一度だけ() {
        val keywords = PaperSearch.parseKeywords("(robot) OR Robot AND grasp*")!!

        assertEquals(listOf("robot", "grasp"), keywords.terms)
        assertEquals("robot grasp", keywords.display)
    }

    @Test
    fun 使える語が無ければnull() {
        assertNull(PaperSearch.parseKeywords("   "))
        assertNull(PaperSearch.parseKeywords("AND OR \"\" ()"))
    }

    @Test
    fun 語の数には上限がある() {
        val keywords = PaperSearch.parseKeywords((1..20).joinToString(" ") { "w$it" })!!

        assertEquals(PaperSearch.MAX_TERMS, keywords.terms.size)
    }

    @Test
    fun arXivの検索は新しく投稿された順のすべての語を含むもの() {
        val url = PaperSearch.feedUrl(PaperSource.ARXIV, PaperSearch.parseKeywords("\"diffusion model\" robot")!!)

        assertEquals(
            "https://export.arxiv.org/api/query?search_query=all%3A%22diffusion+model%22+AND+all%3Arobot" +
                "&sortBy=submittedDate&sortOrder=descending&max_results=${PaperSearch.RESULTS_PER_CHECK}",
            url
        )
        assertEquals(PaperSource.ARXIV, PaperSource.ofFeedUrl(url))
    }

    @Test
    fun EuropePMCの検索はプレプリントとPubMedで絞り込みを変える() {
        val keywords = PaperSearch.parseKeywords("CRISPR \"base editing\"")!!
        val preprints = PaperSearch.feedUrl(PaperSource.PREPRINTS, keywords)
        val pubmed = PaperSearch.feedUrl(PaperSource.PUBMED, keywords)

        assertEquals(
            "https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=%28crispr+AND+%22base+editing%22%29+AND+SRC%3APPR" +
                "&format=json&resultType=lite&pageSize=${PaperSearch.RESULTS_PER_CHECK}&sort=P_PDATE_D+desc",
            preprints
        )
        assertTrue(pubmed.contains("SRC%3AMED"))
        assertEquals(PaperSource.PREPRINTS, PaperSource.ofFeedUrl(preprints))
        assertEquals(PaperSource.PUBMED, PaperSource.ofFeedUrl(pubmed))
        assertNull(PaperSource.ofFeedUrl("https://example.com/feed.xml"))
    }

    @Test
    fun 大文字小文字が違うだけのキーワードは同じURLになる() {
        val a = PaperSearch.feedUrl(PaperSource.ARXIV, PaperSearch.parseKeywords("Graph Neural")!!)
        val b = PaperSearch.feedUrl(PaperSource.ARXIV, PaperSearch.parseKeywords("graph  neural")!!)

        assertEquals(a, b)
    }

    @Test
    fun arXivの版を除いて同じ論文の新しい版を見分ける() {
        val items = listOf(
            FeedItem("http://arxiv.org/abs/2611.26389v2", "https://arxiv.org/abs/2611.26389v2", "論文", null),
            FeedItem("http://arxiv.org/abs/hep-th/9901001v1", "https://arxiv.org/abs/hep-th/9901001v1", "古い論文", null),
            FeedItem("other", "https://example.com/v2", "ほか", null)
        )

        val normalized = PaperSearch.normalizeArxivItems(items)

        assertEquals("https://arxiv.org/abs/2611.26389", normalized[0].key)
        assertEquals("https://arxiv.org/abs/2611.26389", normalized[0].url)
        assertEquals("https://arxiv.org/abs/hep-th/9901001", normalized[1].url)
        assertEquals(items[2], normalized[2])
    }

    @Test
    fun EuropePMCの結果を読む() {
        val json = """
            {"hitCount":3,"resultList":{"result":[
              {"id":"PPR1373840","source":"PPR","doi":"10.64898/2026.11.19.767861",
               "title":"Deletion of &lt;i&gt;Furin&lt;/i&gt; Facilitates  Particle Generation","firstPublicationDate":"2026-11-20"},
              {"id":"43022511","source":"MED","pmid":"43022511","doi":"10.1016/j.synbio.2026.10.009","title":"PubMed の論文",
               "firstPublicationDate":"2026-11-01"},
              {"id":"PPR1","source":"PPR","title":"DOI の無い論文","firstPublicationDate":null},
              {"source":"PPR","title":"ID の無いものは読まない"}
            ]}}
        """.trimIndent()

        val feed = PaperSearch.parseEuropePmc(json.toByteArray())!!

        assertEquals(3, feed.items.size)
        val preprint = feed.items[0]
        assertEquals("europepmc:PPR:PPR1373840", preprint.key)
        assertEquals("https://doi.org/10.64898/2026.11.19.767861", preprint.url)
        assertEquals("Deletion of Furin Facilitates Particle Generation", preprint.title)
        assertEquals(LocalDate.of(2026, 11, 20).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), preprint.publishedAt)
        assertEquals("https://pubmed.ncbi.nlm.nih.gov/43022511/", feed.items[1].url)
        assertEquals("https://europepmc.org/article/PPR/PPR1", feed.items[2].url)
        assertNull(feed.items[2].publishedAt)
    }

    @Test
    fun EuropePMCの結果でなければnull() {
        assertNull(PaperSearch.parseEuropePmc("<html></html>".toByteArray()))
        assertNull(PaperSearch.parseEuropePmc("{\"error\":\"bad query\"}".toByteArray()))
    }
}
