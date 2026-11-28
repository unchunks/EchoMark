package com.unchunks.echomark.data.search

import com.unchunks.components.feed.FeedItem
import com.unchunks.components.feed.FeedParser
import com.unchunks.echomark.domain.subscription.QueryKind
import com.unchunks.echomark.domain.subscription.SearchSource
import com.unchunks.echomark.domain.subscription.SubscriptionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** サイトの検索(論文・ニュース・技術記事・GitHub のリリース)の URL の作り方と、検索の結果の読み方。 */
class SearchFeedsTest {

    private fun valid(source: SearchSource, kind: QueryKind, input: String): SearchFeeds.Query.Valid =
        SearchFeeds.build(source, kind, input) as SearchFeeds.Query.Valid

    private fun invalid(source: SearchSource, kind: QueryKind, input: String): String =
        (SearchFeeds.build(source, kind, input) as SearchFeeds.Query.Invalid).message

    // ---- キーワード ----

    @Test
    fun キーワードは空白で区切り_引用符で囲んだ語句はそのまま() {
        val keywords = SearchFeeds.parseKeywords("  \"Large Language Model\"   agent  ")!!

        assertEquals(listOf("large language model", "agent"), keywords.terms)
        assertEquals("\"Large Language Model\" agent", keywords.display)
    }

    @Test
    fun 検索式の記号と演算子は語から除く_同じ語は一度だけ() {
        val keywords = SearchFeeds.parseKeywords("(robot) OR Robot AND grasp*")!!

        assertEquals(listOf("robot", "grasp"), keywords.terms)
        assertEquals("robot grasp", keywords.display)
    }

    @Test
    fun 使える語が無ければ追加しない() {
        assertNull(SearchFeeds.parseKeywords("   "))
        assertNull(SearchFeeds.parseKeywords("AND OR \"\" ()"))
        assertEquals("キーワードを入れてください", invalid(SearchSource.HATENA, QueryKind.KEYWORDS, " "))
    }

    @Test
    fun 語の数には上限がある() {
        val keywords = SearchFeeds.parseKeywords((1..20).joinToString(" ") { "w$it" })!!

        assertEquals(SearchFeeds.MAX_TERMS, keywords.terms.size)
    }

    @Test
    fun サイトで使えない探し方は断る() {
        assertTrue(invalid(SearchSource.QIITA, QueryKind.KEYWORDS, "kotlin").contains("探せません"))
        assertTrue(invalid(SearchSource.PUBMED, QueryKind.CATEGORY, "cs.AI").contains("探せません"))
    }

    // ---- 論文 ----

    @Test
    fun arXivのキーワードは新しく投稿された順のすべての語を含むもの() {
        val query = valid(SearchSource.ARXIV, QueryKind.KEYWORDS, "\"diffusion model\" robot")

        assertEquals(
            "https://export.arxiv.org/api/query?search_query=all%3A%22diffusion+model%22+AND+all%3Arobot" +
                "&sortBy=submittedDate&sortOrder=descending&max_results=${SearchFeeds.RESULTS_PER_CHECK}",
            query.feedUrl
        )
        assertEquals("\"diffusion model\" robot", query.title)
        assertEquals(SearchSource.ARXIV, SearchSource.ofFeedUrl(query.feedUrl))
        assertEquals(QueryKind.KEYWORDS, SearchSource.ARXIV.queryKindOf(query.feedUrl))
    }

    @Test
    fun arXivの著者は名前の語をすべて含む著者() {
        val query = valid(SearchSource.ARXIV, QueryKind.AUTHOR, " Yann  LeCun ")

        assertTrue(query.feedUrl, query.feedUrl.contains("search_query=au%3Ayann+AND+au%3Alecun&"))
        assertEquals("Yann LeCun", query.title)
        assertEquals(QueryKind.AUTHOR, SearchSource.ARXIV.queryKindOf(query.feedUrl))
        assertEquals("著者の名前を入れてください", invalid(SearchSource.ARXIV, QueryKind.AUTHOR, " () "))
    }

    @Test
    fun arXivの分野は記号だけを受け付ける() {
        val query = valid(SearchSource.ARXIV, QueryKind.CATEGORY, " cs.AI ")

        assertTrue(query.feedUrl, query.feedUrl.contains("search_query=cat%3Acs.AI&"))
        assertEquals("https://arxiv.org/list/cs.AI/new", query.siteUrl)
        assertEquals(QueryKind.CATEGORY, SearchSource.ARXIV.queryKindOf(query.feedUrl))
        valid(SearchSource.ARXIV, QueryKind.CATEGORY, "quant-ph")
        valid(SearchSource.ARXIV, QueryKind.CATEGORY, "cond-mat.mes-hall")
        assertTrue(invalid(SearchSource.ARXIV, QueryKind.CATEGORY, "cs AI").startsWith("分野の記号"))
        assertTrue(invalid(SearchSource.ARXIV, QueryKind.CATEGORY, "cs.AI OR all:x").startsWith("分野の記号"))
    }

    @Test
    fun EuropePMCの検索はプレプリントとPubMedで絞り込みを変える() {
        val preprints = valid(SearchSource.PREPRINTS, QueryKind.KEYWORDS, "CRISPR \"base editing\"").feedUrl
        val pubmed = valid(SearchSource.PUBMED, QueryKind.KEYWORDS, "CRISPR \"base editing\"").feedUrl

        assertEquals(
            "https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=%28crispr+AND+%22base+editing%22%29+AND+SRC%3APPR" +
                "&format=json&resultType=lite&pageSize=${SearchFeeds.RESULTS_PER_CHECK}&sort=P_PDATE_D+desc",
            preprints
        )
        assertTrue(pubmed.contains("SRC%3AMED"))
        assertEquals(SearchSource.PREPRINTS, SearchSource.ofFeedUrl(preprints))
        assertEquals(SearchSource.PUBMED, SearchSource.ofFeedUrl(pubmed))
        assertNull(SearchSource.ofFeedUrl("https://example.com/feed.xml"))
    }

    @Test
    fun EuropePMCの著者はフルネームで探す() {
        val query = valid(SearchSource.PUBMED, QueryKind.AUTHOR, "Jennifer Doudna")

        assertTrue(query.feedUrl, query.feedUrl.contains("query=AUTH%3A%22jennifer+doudna%22+AND+SRC%3AMED&"))
        assertEquals(QueryKind.AUTHOR, SearchSource.PUBMED.queryKindOf(query.feedUrl))
    }

    @Test
    fun 大文字小文字が違うだけのキーワードは同じURLになる() {
        val a = valid(SearchSource.ARXIV, QueryKind.KEYWORDS, "Graph Neural").feedUrl
        val b = valid(SearchSource.ARXIV, QueryKind.KEYWORDS, "graph  neural").feedUrl

        assertEquals(a, b)
    }

    // ---- ニュース・技術記事 ----

    @Test
    fun はてなブックマークは本文を新しい順に探す() {
        val query = valid(SearchSource.HATENA, QueryKind.KEYWORDS, "生成AI \"規制 法案\"")

        assertEquals(
            "https://b.hatena.ne.jp/q/%E7%94%9F%E6%88%90ai%20%22%E8%A6%8F%E5%88%B6%20%E6%B3%95%E6%A1%88%22" +
                "?mode=rss&sort=recent&target=text&users=${SearchFeeds.HATENA_MIN_USERS}",
            query.feedUrl
        )
        assertEquals(SearchSource.HATENA, SearchSource.ofFeedUrl(query.feedUrl))
        assertEquals(SubscriptionType.SEARCH, SearchSource.HATENA.subscriptionType)
    }

    @Test
    fun GDELTは日本語のキーワードなら日本語の記事に絞る() {
        val japanese = valid(SearchSource.GDELT, QueryKind.KEYWORDS, "生成AI").feedUrl
        val english = valid(SearchSource.GDELT, QueryKind.KEYWORDS, "\"generative AI\"").feedUrl

        assertTrue(japanese, japanese.contains("query=%E7%94%9F%E6%88%90ai%20sourcelang%3Ajapanese&"))
        assertTrue(english, english.contains("query=%22generative%20ai%22&mode=artlist&format=rss&sort=datedesc"))
        assertEquals(SearchSource.GDELT, SearchSource.ofFeedUrl(english))
    }

    @Test
    fun HackerNewsはキーワードで新着を探す() {
        val query = valid(SearchSource.HACKER_NEWS, QueryKind.KEYWORDS, "Rust async")

        assertEquals("https://hnrss.org/newest?q=rust+async", query.feedUrl)
        assertEquals(SearchSource.HACKER_NEWS, SearchSource.ofFeedUrl(query.feedUrl))
    }

    @Test
    fun QiitaとZennはタグを1つ() {
        val qiita = valid(SearchSource.QIITA, QueryKind.TAG, " #Kotlin ")
        val zenn = valid(SearchSource.ZENN, QueryKind.TAG, "kotlin")

        assertEquals("https://qiita.com/tags/kotlin/feed", qiita.feedUrl)
        assertEquals("kotlin", qiita.title)
        assertEquals("https://zenn.dev/topics/kotlin/feed", zenn.feedUrl)
        assertEquals(SearchSource.QIITA, SearchSource.ofFeedUrl(qiita.feedUrl))
        assertEquals(SearchSource.ZENN, SearchSource.ofFeedUrl(zenn.feedUrl))
        assertTrue(invalid(SearchSource.QIITA, QueryKind.TAG, "kotlin android").startsWith("タグを1つ"))
        assertTrue(invalid(SearchSource.ZENN, QueryKind.TAG, "../x").startsWith("タグを1つ"))
    }

    @Test
    fun GitHubはownerとrepoかリポジトリのURL() {
        val short = valid(SearchSource.GITHUB_RELEASES, QueryKind.REPOSITORY, "JetBrains/kotlin")
        val url = valid(SearchSource.GITHUB_RELEASES, QueryKind.REPOSITORY, "https://github.com/JetBrains/kotlin/tree/master")

        assertEquals("https://github.com/jetbrains/kotlin/releases.atom", short.feedUrl)
        assertEquals("JetBrains/kotlin", short.title)
        assertEquals(short.feedUrl, url.feedUrl)
        assertEquals(SearchSource.GITHUB_RELEASES, SearchSource.ofFeedUrl(short.feedUrl))
        assertTrue(invalid(SearchSource.GITHUB_RELEASES, QueryKind.REPOSITORY, "kotlin").startsWith("owner/repo"))
        assertTrue(invalid(SearchSource.GITHUB_RELEASES, QueryKind.REPOSITORY, "a/..").startsWith("owner/repo"))
        assertTrue(invalid(SearchSource.GITHUB_RELEASES, QueryKind.REPOSITORY, "a b/c").startsWith("owner/repo"))
    }

    @Test
    fun ふつうのGitHubのフィードはリリースの購読と見なさない() {
        assertNull(SearchSource.ofFeedUrl("https://github.com/JetBrains/kotlin/commits/master.atom"))
    }

    // ---- 結果の読み方 ----

    @Test
    fun arXivの版を除いて同じ論文の新しい版を見分ける() {
        val items = listOf(
            FeedItem("http://arxiv.org/abs/2611.26389v2", "https://arxiv.org/abs/2611.26389v2", "論文", null),
            FeedItem("http://arxiv.org/abs/hep-th/9901001v1", "https://arxiv.org/abs/hep-th/9901001v1", "古い論文", null),
            FeedItem("other", "https://example.com/v2", "ほか", null)
        )

        val normalized = SearchFeeds.normalizeArxivItems(items)

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

        val feed = SearchFeeds.parseEuropePmc(json.toByteArray())!!

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
        assertNull(SearchFeeds.parseEuropePmc("<html></html>".toByteArray()))
        assertNull(SearchFeeds.parseEuropePmc("{\"error\":\"bad query\"}".toByteArray()))
    }

    @Test
    fun GDELTの曜日の無い日時とはてなブックマークのRSS1_0を読める() {
        val gdelt = "<rss version=\"2.0\"><channel><title>GDELT</title><item><title>News</title>" +
            "<link>https://news.example.com/a</link><pubDate>23 Nov 2026 02:30:00 +0000</pubDate></item></channel></rss>"
        val hatena = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" " +
            "xmlns=\"http://purl.org/rss/1.0/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><channel><title>はてな</title></channel>" +
            "<item rdf:about=\"https://blog.example.jp/entry/1\"><title>記事</title><link>https://blog.example.jp/entry/1</link>" +
            "<dc:date>2026-11-23T02:12:25Z</dc:date></item></rdf:RDF>"

        val gdeltItem = FeedParser.parse(gdelt, "https://api.gdeltproject.org/api/v2/doc/doc")!!.items.single()
        val hatenaItem = FeedParser.parse(hatena, "https://b.hatena.ne.jp/q/x")!!.items.single()

        assertEquals(java.time.Instant.parse("2026-11-23T02:30:00Z").toEpochMilli(), gdeltItem.publishedAt)
        assertEquals("https://blog.example.jp/entry/1", hatenaItem.url)
        assertEquals(java.time.Instant.parse("2026-11-23T02:12:25Z").toEpochMilli(), hatenaItem.publishedAt)
    }
}
