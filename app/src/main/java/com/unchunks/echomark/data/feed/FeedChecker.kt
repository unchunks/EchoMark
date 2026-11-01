package com.unchunks.echomark.data.feed

import com.unchunks.echomark.data.local.dao.SubscriptionDao
import com.unchunks.echomark.data.local.entity.SubscriptionEntity
import com.unchunks.echomark.domain.importer.ImportedLink
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.subscription.FeedCheckSummary
import com.unchunks.echomark.domain.subscription.INITIAL_ITEMS_PER_FEED
import com.unchunks.echomark.domain.subscription.MAX_NEW_ITEMS_PER_FEED
import com.unchunks.echomark.domain.subscription.MAX_NEW_ITEMS_PER_RUN
import com.unchunks.echomark.domain.subscription.SubscriptionType
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * 購読しているフィードを確認して、新しい記事・動画をブックマークとして保存する(#78)。
 *
 * - 新しいかどうかは「見た記事の目印」([SubscriptionEntity.seenKeys])で決める。削除した記事が、次の確認で戻ってこない
 * - 初めて確認するときは、最新の [INITIAL_ITEMS_PER_FEED] 件だけ保存し、ほかは「見た」ことにする(昔の記事を一度に流し込まない)
 * - 1つのフィードから [MAX_NEW_ITEMS_PER_FEED] 件、全体で [MAX_NEW_ITEMS_PER_RUN] 件まで。超えた分は保存せず(「見た」ことにもせず)、次の確認に回る
 * - フィードの ID が変わって古い記事が新しく見えたときに流れ込まないよう、日付が分かる記事は、購読を始めた日・前回確認できた日より
 *   [GRACE_MILLIS] 以上前のものを保存せずに「見た」ことにする
 * - 保存は [BookmarkRepository.importLinks] で1回にまとめて行う。ページの取得と要約は、一括の再処理と同じ1本の順番の列で1件ずつ行う
 *   (記事ごとの急ぎの処理は積まない)。夜間にまとめて処理する設定なら、処理待ちのまま夜を待つ
 * - 続けて失敗しているフィードは、[BACKOFF_FAILURES] 回を超えたら [BACKOFF_MILLIS] ごとにしか確認しない(手動の確認は除く)
 */
class FeedChecker @Inject constructor(
    private val subscriptionDao: SubscriptionDao,
    private val fetcher: FeedFetcher,
    private val bookmarkRepository: Lazy<BookmarkRepository>
) {

    /** 有効なすべてのフィードを確認する。[maxNew] は全体で新しく保存する記事の上限。[manual] は利用者が押した確認(失敗の間引きをしない) */
    suspend fun checkAll(maxNew: Int = MAX_NEW_ITEMS_PER_RUN, manual: Boolean = false, now: Long = System.currentTimeMillis()): FeedCheckSummary =
        check(subscriptionDao.getEnabled(), maxNew, manual, now)

    /** 1つのフィードを確認する(購読を追加した直後・手動の確認)。無い・無効なら何もしない。 */
    suspend fun checkOne(id: Long, maxNew: Int = MAX_NEW_ITEMS_PER_RUN, now: Long = System.currentTimeMillis()): FeedCheckSummary {
        val subscription = subscriptionDao.getById(id)?.takeIf { it.enabled } ?: return FeedCheckSummary()
        return check(listOf(subscription), maxNew, manual = true, now = now)
    }

    private class Pending(
        val subscription: SubscriptionEntity,
        val title: String,
        val siteUrl: String?,
        val etag: String?,
        val lastModified: String?,
        val plan: Plan
    )

    private suspend fun check(subscriptions: List<SubscriptionEntity>, maxNew: Int, manual: Boolean, now: Long): FeedCheckSummary {
        // 長く確認していないものを先に(上限で打ち切っても、同じフィードばかり確認して残りが後回しにならない)
        val ordered = subscriptions.sortedWith(compareBy({ it.lastCheckedAt ?: Long.MIN_VALUE }, { it.id }))
        var remaining = maxNew
        var checked = 0
        var failed = 0
        val pending = mutableListOf<Pending>()

        for (subscription in ordered) {
            if (remaining <= 0) break
            if (!manual && isBackedOff(subscription, now)) continue
            checked++
            try {
                when (val response = fetcher.fetch(subscription.feedUrl, subscription.etag, subscription.lastModified)) {
                    FeedResponse.NotModified -> subscriptionDao.recordNotModified(subscription.id, now)
                    is FeedResponse.Body -> {
                        val parsed = FeedParser.parse(response.body.bytes, response.body.charsetName, response.body.finalUrl)
                        if (parsed == null) {
                            failed++
                            subscriptionDao.recordFailure(subscription.id, now, NOT_A_FEED_MESSAGE)
                        } else {
                            val plan = planNewItems(subscription, parsed.items, now, minOf(MAX_NEW_ITEMS_PER_FEED, remaining))
                            remaining -= plan.toSave.size
                            pending += Pending(
                                subscription, parsed.title, parsed.siteUrl, response.body.etag, response.body.lastModified, plan
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: FeedFetchException) {
                failed++
                subscriptionDao.recordFailure(subscription.id, now, e.message ?: "取得できませんでした")
            } catch (e: Exception) {
                Timber.w(e, "フィードの確認に失敗: %s", subscription.feedUrl)
                failed++
                subscriptionDao.recordFailure(subscription.id, now, "確認できませんでした")
            }
        }

        // 保存は全部のフィード分を1回にまとめる(順番の列に1回だけ積む)。保存に失敗したら、見た記事の目印を更新せず次に回す
        val links = pending.flatMap { p -> p.plan.toSave.map { toLink(it, p) } }
        var added = 0
        if (links.isNotEmpty()) {
            try {
                added = bookmarkRepository.get().importLinks(links, folderAsTag = false).added
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "新着の保存に失敗")
                pending.forEach { subscriptionDao.recordFailure(it.subscription.id, now, "新着を保存できませんでした") }
                return FeedCheckSummary(checked = checked, failed = failed + pending.size, added = 0)
            }
        }
        for (p in pending) {
            subscriptionDao.recordSuccess(
                id = p.subscription.id,
                title = p.title.ifBlank { p.subscription.title },
                siteUrl = p.siteUrl,
                checkedAt = now,
                etag = p.etag,
                lastModified = p.lastModified,
                seenKeys = p.plan.seenKeys
            )
        }
        return FeedCheckSummary(checked = checked, failed = failed, added = added)
    }

    private fun toLink(item: FeedItem, pending: Pending): ImportedLink {
        val feedTag = tagOf(pending.title.ifBlank { pending.subscription.title })
        val tags = buildList {
            if (feedTag.isNotEmpty()) add(feedTag)
            if (pending.subscription.type == SubscriptionType.YOUTUBE.name) add("YouTube")
        }
        // 保存した日時は今(新着として一覧の先頭に出す)。記事の公開日時は使わない
        return ImportedLink(url = item.url, title = item.title, tags = tags)
    }

    private fun isBackedOff(subscription: SubscriptionEntity, now: Long): Boolean =
        subscription.failureCount >= BACKOFF_FAILURES &&
            now - (subscription.lastCheckedAt ?: 0L) < BACKOFF_MILLIS

    /** 新しく保存する記事 [toSave] と、確認後の見た記事の目印 [seenKeys] */
    class Plan(val toSave: List<FeedItem>, val seenKeys: String)

    companion object {
        /** 日付が分かる記事を新着とみなす境目の余裕(前回確認した日・購読を始めた日より、これ以上古いものは新着にしない) */
        val GRACE_MILLIS: Long = TimeUnit.DAYS.toMillis(2)

        /** 続けて失敗したら間引きを始める回数 */
        const val BACKOFF_FAILURES = 5

        /** 間引いたあとの確認の間隔 */
        val BACKOFF_MILLIS: Long = TimeUnit.DAYS.toMillis(7)

        /** 見た記事の目印として覚える最大数(1つのフィードから読む最大数より十分多くする) */
        const val MAX_SEEN_KEYS = 500

        private const val NOT_A_FEED_MESSAGE = "フィードとして読めませんでした(RSS・Atom ではありません)"
        private const val MAX_TAG_CHARS = 40

        /** フィードのタイトルからタグの名前を作る(前後の空白を除き、長すぎるものは切る) */
        internal fun tagOf(title: String): String = title.trim().take(MAX_TAG_CHARS).trim()

        /**
         * 取得した [items] のうち、新しく保存するものと、確認後の見た記事の目印を決める。
         * [limit] はこのフィードで新しく保存する最大数。
         */
        internal fun planNewItems(subscription: SubscriptionEntity, items: List<FeedItem>, now: Long, limit: Int): Plan {
            val seen = subscription.seenKeys.split('\n').filter { it.isNotEmpty() }
            val seenSet = seen.toHashSet()
            // 新しい順(日付が分かるものは日付で。分からないものはフィードの並びのまま後ろに)
            val sorted = items.withIndex()
                .sortedWith(compareBy({ it.value.publishedAt == null }, { -(it.value.publishedAt ?: 0L) }, { it.index }))
                .map { it.value }

            val firstCheck = subscription.lastSuccessAt == null
            val toSave: List<FeedItem>
            val skipped: List<FeedItem>
            if (firstCheck) {
                toSave = sorted.take(minOf(INITIAL_ITEMS_PER_FEED, limit))
                // 初めての確認では、保存しなかった分も含め、いま載っているものは全部「見た」ことにする
                skipped = sorted.drop(toSave.size)
            } else {
                val watermark = maxOf(subscription.createdAt, subscription.lastSuccessAt ?: 0L) - GRACE_MILLIS
                val unseen = sorted.filter { it.key !in seenSet }
                val fresh = unseen.filter { it.publishedAt == null || it.publishedAt >= watermark }
                toSave = fresh.take(limit)
                // 古い日付の記事は保存せず「見た」ことにする。上限で保存しなかった新着は、次の確認に回すので「見た」にしない
                skipped = unseen.filter { it.publishedAt != null && it.publishedAt < watermark }
            }
            val newSeen = (seen + toSave.map { it.key } + skipped.map { it.key }).distinct().takeLast(MAX_SEEN_KEYS)
            return Plan(toSave, newSeen.joinToString("\n"))
        }
    }
}
