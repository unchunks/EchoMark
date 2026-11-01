package com.unchunks.echomark.data.subscription

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.data.feed.FeedBody
import com.unchunks.echomark.data.feed.FeedChecker
import com.unchunks.echomark.data.feed.FeedDiscovery
import com.unchunks.echomark.data.feed.FeedFetchException
import com.unchunks.echomark.data.feed.FeedFetcher
import com.unchunks.echomark.data.feed.FeedParser
import com.unchunks.echomark.data.feed.FeedResponse
import com.unchunks.echomark.data.feed.ParsedFeed
import com.unchunks.echomark.data.local.dao.SubscriptionDao
import com.unchunks.echomark.data.local.entity.SubscriptionEntity
import com.unchunks.echomark.domain.repository.SubscriptionRepository
import com.unchunks.echomark.domain.subscription.FeedCheckSummary
import com.unchunks.echomark.domain.subscription.MAX_SUBSCRIPTIONS
import com.unchunks.echomark.domain.subscription.SubscribeOutcome
import com.unchunks.echomark.domain.subscription.Subscription
import com.unchunks.echomark.domain.subscription.SubscriptionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.URI
import java.util.Locale
import javax.inject.Inject

class SubscriptionRepositoryImpl @Inject constructor(
    private val dao: SubscriptionDao,
    private val fetcher: FeedFetcher,
    private val checker: FeedChecker,
    private val dispatcherProvider: DispatcherProvider
) : SubscriptionRepository {

    override fun observeAll(): Flow<List<Subscription>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }.flowOn(dispatcherProvider.io)

    override suspend fun subscribe(input: String): SubscribeOutcome = withContext(dispatcherProvider.io) {
        if (dao.count() >= MAX_SUBSCRIPTIONS) {
            return@withContext SubscribeOutcome.Failed("購読できるのは${MAX_SUBSCRIPTIONS}件までです")
        }
        val url = FeedDiscovery.normalizeInput(input)
            ?: return@withContext SubscribeOutcome.Failed("URL の形式が正しくありません(サイトの URL、フィードの URL、YouTube のチャンネルの URL を入れてください)")
        val found = try {
            resolveFeed(FeedDiscovery.youtubeFeedUrl(url) ?: url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FeedFetchException) {
            return@withContext SubscribeOutcome.Failed(e.message ?: "取得できませんでした")
        } catch (e: Exception) {
            Timber.w(e, "購読の追加でフィードを読めなかった")
            return@withContext SubscribeOutcome.Failed("フィードを読めませんでした")
        } ?: return@withContext SubscribeOutcome.Failed("フィード(RSS・Atom)が見つかりませんでした。フィードの URL を直接入れてみてください")

        val (feedUrl, feed) = found
        val id = dao.insertIgnore(
            SubscriptionEntity(
                type = feed.type.name,
                feedUrl = feedUrl,
                title = feed.title,
                siteUrl = feed.siteUrl,
                createdAt = System.currentTimeMillis()
            )
        )
        if (id == -1L) SubscribeOutcome.Duplicate(feed.title) else SubscribeOutcome.Added(id, feed.title, feed.type)
    }

    /**
     * [url] がフィードならそれを、HTML のページならそこから探したフィードを取得して読む。(フィードの URL, 読んだ内容)。見つからなければ null。
     * フィードの URL はリダイレクトを追った後のもの(同じフィードを別の URL で二重に購読しないため)。
     */
    private suspend fun resolveFeed(url: String): Pair<String, ParsedFeed>? {
        val first = fetchBody(url)
        parse(first)?.let { return first.finalUrl to it }

        // フィードではなかった。ページなら、ページの中からフィードを探す
        val html = first.text()
        val discovered = FeedDiscovery.feedUrlFromHtml(html, first.finalUrl)
            ?: FeedDiscovery.youtubeFeedUrlFromHtml(html).takeIf { isYouTube(first.finalUrl) }
            ?: return null
        val second = fetchBody(discovered)
        return parse(second)?.let { second.finalUrl to it }
    }

    private suspend fun fetchBody(url: String): FeedBody = when (val response = fetcher.fetch(url)) {
        is FeedResponse.Body -> response.body
        // 条件を付けずに取得したので来ない
        FeedResponse.NotModified -> throw FeedFetchException("取得できませんでした")
    }

    private fun parse(body: FeedBody): ParsedFeed? = FeedParser.parse(body.bytes, body.charsetName, body.finalUrl)

    private fun isYouTube(url: String): Boolean {
        val host = try {
            URI(url).host?.lowercase(Locale.ROOT)
        } catch (e: Exception) {
            null
        } ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com")
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) = withContext(dispatcherProvider.io) {
        dao.setEnabled(id, enabled)
    }

    override suspend fun remove(id: Long) = withContext(dispatcherProvider.io) {
        dao.delete(id)
    }

    override suspend fun checkNow(id: Long?): FeedCheckSummary =
        if (id == null) checker.checkAll(manual = true) else checker.checkOne(id)
}

internal fun SubscriptionEntity.toDomain(): Subscription = Subscription(
    id = id,
    // 読めない種類(将来の版で増えたもの)は RSS として扱う
    type = SubscriptionType.entries.firstOrNull { it.name == type } ?: SubscriptionType.RSS,
    feedUrl = feedUrl,
    title = title,
    siteUrl = siteUrl,
    createdAt = createdAt,
    enabled = enabled,
    lastCheckedAt = lastCheckedAt,
    failureCount = failureCount,
    lastError = lastError
)
