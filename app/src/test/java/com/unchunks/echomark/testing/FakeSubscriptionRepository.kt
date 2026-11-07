package com.unchunks.echomark.testing

import com.unchunks.echomark.domain.repository.SubscriptionRepository
import com.unchunks.echomark.domain.subscription.FeedCheckSummary
import com.unchunks.echomark.domain.subscription.SubscribeOutcome
import com.unchunks.echomark.domain.subscription.Subscription
import com.unchunks.echomark.domain.subscription.SubscriptionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** 購読の画面のテスト用の仮の実装。通信せず、渡された結果をそのまま返す。 */
class FakeSubscriptionRepository : SubscriptionRepository {
    val subscriptions = MutableStateFlow<List<Subscription>>(emptyList())

    /** subscribe が返す結果。null なら入力から追加する */
    var subscribeOutcome: SubscribeOutcome? = null
    var checkSummary = FeedCheckSummary()
    val subscribed = mutableListOf<String>()
    val checked = mutableListOf<Long?>()
    private var nextId = 1L

    override fun observeAll(): Flow<List<Subscription>> = subscriptions

    override suspend fun subscribe(input: String): SubscribeOutcome {
        subscribed += input
        subscribeOutcome?.let { return it }
        val id = nextId++
        subscriptions.value = subscriptions.value + Subscription(
            id, SubscriptionType.RSS, input, "購読 $id", null, createdAt = id, enabled = true, lastCheckedAt = null, failureCount = 0, lastError = null
        )
        return SubscribeOutcome.Added(id, "購読 $id", SubscriptionType.RSS)
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) {
        subscriptions.value = subscriptions.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    override suspend fun remove(id: Long) {
        subscriptions.value = subscriptions.value.filterNot { it.id == id }
    }

    override suspend fun checkNow(id: Long?): FeedCheckSummary {
        checked += id
        return checkSummary
    }
}
