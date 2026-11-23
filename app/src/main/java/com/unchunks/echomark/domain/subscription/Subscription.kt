package com.unchunks.echomark.domain.subscription

/** 購読するフィードの種類 */
enum class SubscriptionType(val label: String) {
    /** RSS・Atom のフィード(ブログ・ニュースサイトなど) */
    RSS("RSS・Atom"),

    /** YouTube のチャンネル・再生リストのフィード */
    YOUTUBE("YouTube"),

    /**
     * 論文サイトのキーワード検索(arXiv・Europe PMC)。検索の URL を [Subscription.feedUrl] に、キーワードを [Subscription.title] に持つ。
     * どのサイトかは [PaperSource.ofFeedUrl] で URL から決める(DB に列を足さない)。
     */
    PAPER("論文")
}

/**
 * キーワードで新着論文を購読できるサイト。
 * @property label 画面に出す名前
 * @property tag 保存した論文に付けるタグ(キーワードのタグと一緒に付ける)
 * @property description 何が見つかるか(画面に出す説明)
 */
enum class PaperSource(val label: String, val tag: String, val description: String) {
    /** arXiv の API(Atom を返す)。物理・数学・情報科学・統計など */
    ARXIV("arXiv", "arXiv", "物理・数学・情報科学・統計などのプレプリント"),

    /** Europe PMC のプレプリント(bioRxiv・medRxiv・Research Square など)。JSON を返す */
    PREPRINTS("bioRxiv・medRxiv など", "プレプリント", "生命科学・医学のプレプリント(bioRxiv・medRxiv など。Europe PMC で検索)"),

    /** Europe PMC の PubMed の論文。JSON を返す */
    PUBMED("PubMed", "PubMed", "生命科学・医学の論文(PubMed。Europe PMC で検索)");

    companion object {
        /** 検索の URL からサイトを決める。論文の検索の URL でなければ null */
        fun ofFeedUrl(feedUrl: String): PaperSource? = when {
            feedUrl.startsWith(ARXIV_API_URL) -> ARXIV
            feedUrl.startsWith(EUROPE_PMC_API_URL) && feedUrl.contains("SRC%3AMED") -> PUBMED
            feedUrl.startsWith(EUROPE_PMC_API_URL) -> PREPRINTS
            else -> null
        }

        /** arXiv の検索の API */
        const val ARXIV_API_URL = "https://export.arxiv.org/api/query"

        /** Europe PMC の検索の API */
        const val EUROPE_PMC_API_URL = "https://www.ebi.ac.uk/europepmc/webservices/rest/search"
    }
}

/**
 * 購読しているフィード(#78)。夜間に新しい記事・動画を確かめて、ブックマークとして保存する。
 * @property lastCheckedAt 最後に確認した日時。まだ一度も確認していなければ null
 * @property failureCount 続けて確認に失敗した回数
 * @property lastError 最後の失敗の理由(利用者に見せる文)。うまくいっていれば null
 */
data class Subscription(
    val id: Long,
    val type: SubscriptionType,
    val feedUrl: String,
    val title: String,
    val siteUrl: String?,
    val createdAt: Long,
    val enabled: Boolean,
    val lastCheckedAt: Long?,
    val failureCount: Int,
    val lastError: String?
) {
    /** 論文の購読なら、検索するサイト。それ以外は null */
    val paperSource: PaperSource?
        get() = if (type == SubscriptionType.PAPER) PaperSource.ofFeedUrl(feedUrl) else null
}

/** 購読を追加した結果 */
sealed interface SubscribeOutcome {
    /** 追加した(最初の確認は呼び出し側で行う) */
    data class Added(val id: Long, val title: String, val type: SubscriptionType) : SubscribeOutcome

    /** 同じフィードを既に購読している */
    data class Duplicate(val title: String) : SubscribeOutcome

    /** 追加できなかった。[message] は利用者にそのまま見せる */
    data class Failed(val message: String) : SubscribeOutcome
}

/**
 * フィードの確認の結果。
 * @property checked 確認したフィードの数(変わっていなかったものも含む)
 * @property failed 確認に失敗したフィードの数
 * @property added 新しくブックマークに保存した記事・動画の数
 */
data class FeedCheckSummary(val checked: Int = 0, val failed: Int = 0, val added: Int = 0)

/** 1回の確認で、すべてのフィードを合わせて新しく保存する記事・動画の上限(流れ込みすぎて処理があふれないように) */
const val MAX_NEW_ITEMS_PER_RUN = 30

/** 1つのフィードから、1回の確認で新しく保存する記事・動画の上限 */
const val MAX_NEW_ITEMS_PER_FEED = 10

/** 購読を追加して最初に確認するとき、保存する最新の記事・動画の数(それより古いものは「見た」ことにする) */
const val INITIAL_ITEMS_PER_FEED = 3

/** 購読できるフィードの数の上限 */
const val MAX_SUBSCRIPTIONS = 100
