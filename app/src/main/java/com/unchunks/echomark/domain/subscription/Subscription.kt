package com.unchunks.echomark.domain.subscription

/** 購読するフィードの種類 */
enum class SubscriptionType(val label: String) {
    /** RSS・Atom のフィード(ブログ・ニュースサイトなど) */
    RSS("RSS・Atom"),

    /** YouTube のチャンネル・再生リストのフィード */
    YOUTUBE("YouTube")
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
)

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
