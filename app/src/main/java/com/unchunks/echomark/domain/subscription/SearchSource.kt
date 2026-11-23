package com.unchunks.echomark.domain.subscription

/** 検索するサイトのまとまり(画面で並べる順) */
enum class SearchGroup(val label: String) {
    PAPER("論文"),
    NEWS("ニュース"),
    TECH("技術・開発")
}

/** 何で探すか */
enum class QueryKind(val label: String) {
    /** 空白で区切った語をすべて含むもの */
    KEYWORDS("キーワード"),

    /** 著者の名前 */
    AUTHOR("著者"),

    /** arXiv の分野(cs.AI など) */
    CATEGORY("分野"),

    /** サイトのタグ・トピック */
    TAG("タグ"),

    /** GitHub のリポジトリ(owner/repo) */
    REPOSITORY("リポジトリ")
}

/**
 * キーワード・タグなどで新着を購読できるサイト。購読は検索の URL を持ち、サイトは URL から決める([ofFeedUrl])。
 * @property label 画面に出す名前
 * @property tag 保存したブックマークに付けるタグ(キーワードなどのタグと一緒に付ける)
 * @property description 何が見つかるか(画面に出す説明)
 * @property kinds 探し方(最初のものが既定)
 * @property requestSpacingMillis 続けて問い合わせるときに空ける間隔(サイトが求める問い合わせの頻度を守る)。0 なら空けない
 */
enum class SearchSource(
    val label: String,
    val tag: String,
    val description: String,
    val group: SearchGroup,
    val kinds: List<QueryKind>,
    val requestSpacingMillis: Long = 0L
) {
    /** arXiv の API(Atom を返す)。3秒に1回までを求めている */
    ARXIV(
        "arXiv", "arXiv", "物理・数学・情報科学・統計などのプレプリント", SearchGroup.PAPER,
        listOf(QueryKind.KEYWORDS, QueryKind.AUTHOR, QueryKind.CATEGORY), requestSpacingMillis = 3_500L
    ),

    /** Europe PMC のプレプリント(bioRxiv・medRxiv・Research Square など)。JSON を返す */
    PREPRINTS(
        "bioRxiv・medRxiv など", "プレプリント", "生命科学・医学のプレプリント(bioRxiv・medRxiv など。Europe PMC で検索)",
        SearchGroup.PAPER, listOf(QueryKind.KEYWORDS, QueryKind.AUTHOR)
    ),

    /** Europe PMC の PubMed の論文。JSON を返す */
    PUBMED(
        "PubMed", "PubMed", "生命科学・医学の論文(PubMed。Europe PMC で検索)",
        SearchGroup.PAPER, listOf(QueryKind.KEYWORDS, QueryKind.AUTHOR)
    ),

    /** はてなブックマークの本文の検索(RSS 1.0)。3人以上がブックマークした記事 */
    HATENA(
        "はてなブックマーク", "はてなブックマーク", "日本語のニュース・ブログの記事(3人以上がブックマークしたもの)",
        SearchGroup.NEWS, listOf(QueryKind.KEYWORDS)
    ),

    /** GDELT の記事の検索(RSS)。5秒に1回までを求めている */
    GDELT(
        "世界のニュース", "ニュース", "世界のニュースサイトの記事(GDELT で検索。日本語のキーワードなら日本語の記事)",
        SearchGroup.NEWS, listOf(QueryKind.KEYWORDS), requestSpacingMillis = 5_500L
    ),

    /** hnrss の Hacker News の検索(RSS) */
    HACKER_NEWS(
        "Hacker News", "Hacker News", "英語の技術ニュース(Hacker News に投稿された記事)",
        SearchGroup.TECH, listOf(QueryKind.KEYWORDS)
    ),

    /** Qiita のタグのフィード(Atom) */
    QIITA("Qiita", "Qiita", "日本語の技術記事(タグの新着)", SearchGroup.TECH, listOf(QueryKind.TAG)),

    /** Zenn のトピックのフィード(RSS) */
    ZENN("Zenn", "Zenn", "日本語の技術記事(トピックの新着)", SearchGroup.TECH, listOf(QueryKind.TAG)),

    /** GitHub のリポジトリのリリースのフィード(Atom) */
    GITHUB_RELEASES(
        "GitHub のリリース", "GitHub", "リポジトリの新しいリリース(リリースノートのページを保存)",
        SearchGroup.TECH, listOf(QueryKind.REPOSITORY)
    );

    /** 購読の種類(論文は [SubscriptionType.PAPER]、ほかは [SubscriptionType.SEARCH]) */
    val subscriptionType: SubscriptionType
        get() = if (group == SearchGroup.PAPER) SubscriptionType.PAPER else SubscriptionType.SEARCH

    /** 検索の URL から、何で探しているかを決める */
    fun queryKindOf(feedUrl: String): QueryKind = when {
        this == ARXIV && feedUrl.contains("search_query=au%3A") -> QueryKind.AUTHOR
        this == ARXIV && feedUrl.contains("search_query=cat%3A") -> QueryKind.CATEGORY
        (this == PREPRINTS || this == PUBMED) && feedUrl.contains("AUTH%3A") -> QueryKind.AUTHOR
        else -> kinds.first()
    }

    companion object {
        /** 検索の URL からサイトを決める。検索の URL でなければ null */
        fun ofFeedUrl(feedUrl: String): SearchSource? = when {
            feedUrl.startsWith(ARXIV_API_URL) -> ARXIV
            feedUrl.startsWith(EUROPE_PMC_API_URL) && feedUrl.contains("SRC%3AMED") -> PUBMED
            feedUrl.startsWith(EUROPE_PMC_API_URL) -> PREPRINTS
            feedUrl.startsWith(HATENA_SEARCH_URL) -> HATENA
            feedUrl.startsWith(GDELT_API_URL) -> GDELT
            feedUrl.startsWith(HNRSS_URL) -> HACKER_NEWS
            feedUrl.startsWith(QIITA_TAGS_URL) -> QIITA
            feedUrl.startsWith(ZENN_TOPICS_URL) -> ZENN
            feedUrl.startsWith(GITHUB_URL) && feedUrl.endsWith("/releases.atom") -> GITHUB_RELEASES
            else -> null
        }

        /** 画面で並べる順(まとまりごと) */
        val grouped: Map<SearchGroup, List<SearchSource>>
            get() = entries.groupBy { it.group }

        const val ARXIV_API_URL = "https://export.arxiv.org/api/query"
        const val EUROPE_PMC_API_URL = "https://www.ebi.ac.uk/europepmc/webservices/rest/search"
        const val HATENA_SEARCH_URL = "https://b.hatena.ne.jp/q/"
        const val GDELT_API_URL = "https://api.gdeltproject.org/api/v2/doc/doc"
        const val HNRSS_URL = "https://hnrss.org/newest"
        const val QIITA_TAGS_URL = "https://qiita.com/tags/"
        const val ZENN_TOPICS_URL = "https://zenn.dev/topics/"
        const val GITHUB_URL = "https://github.com/"
    }
}
