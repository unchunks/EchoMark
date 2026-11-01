package com.unchunks.echomark.ui.subscription

import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.echomark.domain.repository.SubscriptionRepository
import com.unchunks.echomark.domain.subscription.FeedCheckSummary
import com.unchunks.echomark.domain.subscription.SubscribeOutcome
import com.unchunks.echomark.domain.subscription.Subscription
import com.unchunks.echomark.domain.subscription.SubscriptionType
import com.unchunks.echomark.testing.FakeSubscriptionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repository = FakeSubscriptionRepository()

    private fun runWith(block: suspend TestScope.(SubscriptionViewModel) -> Unit) = runTest {
        val viewModel = SubscriptionViewModel(repository)
        // 状態は購読されている間だけ更新される
        viewModel.uiState.launchIn(backgroundScope)
        runCurrent()
        block(viewModel)
    }

    @Test
    fun 追加するとすぐ最新の記事を確認して結果を出す() = runWith { viewModel ->
        repository.checkSummary = FeedCheckSummary(checked = 1, added = 3)
        var added = false

        viewModel.add("https://example.com/") { added = true }
        runCurrent()

        assertTrue(added)
        assertEquals(listOf("https://example.com/"), repository.subscribed)
        assertEquals(listOf<Long?>(1L), repository.checked)
        val state = viewModel.uiState.value
        assertEquals(SubscriptionOperation.Idle, state.operation)
        assertTrue(state.message!!.contains("3 件"))
        assertEquals(1, state.subscriptions.size)
    }

    @Test
    fun 追加できなかった理由をそのまま出す_入力は残す() = runWith { viewModel ->
        repository.subscribeOutcome = SubscribeOutcome.Failed("フィード(RSS・Atom)が見つかりませんでした")
        var added = false

        viewModel.add("https://example.com/page") { added = true }
        runCurrent()

        assertFalse(added)
        assertEquals("フィード(RSS・Atom)が見つかりませんでした", viewModel.uiState.value.message)
        assertTrue(repository.checked.isEmpty())
    }

    @Test
    fun 既に購読しているフィードは知らせる() = runWith { viewModel ->
        repository.subscribeOutcome = SubscribeOutcome.Duplicate("開発ブログ")

        viewModel.add("https://example.com/feed")
        runCurrent()

        assertTrue(viewModel.uiState.value.message!!.contains("すでに購読"))
    }

    @Test
    fun 予想外の失敗でも操作の状態が戻る() = runWith { viewModel ->
        val failing = object : SubscriptionRepository by repository {
            override suspend fun subscribe(input: String): SubscribeOutcome = throw IllegalStateException("boom")
        }
        val failingViewModel = SubscriptionViewModel(failing)
        failingViewModel.uiState.launchIn(backgroundScope)
        runCurrent()

        failingViewModel.add("https://example.com/")
        runCurrent()

        assertEquals(SubscriptionOperation.Idle, failingViewModel.uiState.value.operation)
        assertTrue(failingViewModel.uiState.value.message!!.contains("追加できませんでした"))
    }

    @Test
    fun いま確認すると結果の件数を出す() = runWith { viewModel ->
        repository.subscriptions.value = listOf(subscription(1))
        repository.checkSummary = FeedCheckSummary(checked = 2, failed = 1, added = 4)

        viewModel.checkNow()
        runCurrent()

        assertEquals(listOf<Long?>(null), repository.checked)
        val message = viewModel.uiState.value.message!!
        assertTrue(message, message.contains("4 件") && message.contains("1 件"))
    }

    @Test
    fun オン_オフと削除は購読の一覧に反映する() = runWith { viewModel ->
        repository.subscriptions.value = listOf(subscription(1), subscription(2))

        viewModel.setEnabled(1, false)
        viewModel.remove(2)
        runCurrent()

        val list = viewModel.uiState.value.subscriptions
        assertEquals(listOf(1L), list.map { it.id })
        assertFalse(list.single().enabled)
    }

    @Test
    fun 結果のメッセージは閉じられる() = runWith { viewModel ->
        repository.subscribeOutcome = SubscribeOutcome.Failed("失敗")
        viewModel.add("https://example.com/")
        runCurrent()

        viewModel.dismissMessage()
        runCurrent()

        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun 結果の文() {
        assertTrue(SubscriptionViewModel.addedMessage("ブログ", FeedCheckSummary(checked = 1, added = 0)).contains("毎晩"))
        assertTrue(SubscriptionViewModel.addedMessage("ブログ", FeedCheckSummary(checked = 1, failed = 1)).contains("あとで確認"))
        assertEquals("確認するフィードがありません", SubscriptionViewModel.checkedMessage(FeedCheckSummary()))
        assertEquals("新着はありませんでした", SubscriptionViewModel.checkedMessage(FeedCheckSummary(checked = 2)))
        assertEquals("1 件のフィードを確認できませんでした", SubscriptionViewModel.checkedMessage(FeedCheckSummary(checked = 1, failed = 1)))
    }

    @Test
    fun 状態の一行は失敗_未確認_確認済み_オフで変わる() {
        val now = 10_000_000L
        assertEquals("オフ(確認しません)", statusLabel(subscription(1).copy(enabled = false), now))
        assertEquals("確認できません: HTTP 500", statusLabel(subscription(1).copy(lastError = "HTTP 500", failureCount = 2), now))
        assertTrue(statusLabel(subscription(1), now).contains("まだ確認していません"))
        assertTrue(statusLabel(subscription(1).copy(lastCheckedAt = now - 3_600_000L), now).startsWith("最後の確認:"))
    }

    private fun subscription(id: Long) = Subscription(
        id, SubscriptionType.RSS, "https://example.com/$id", "購読 $id", null, createdAt = id, enabled = true,
        lastCheckedAt = null, failureCount = 0, lastError = null
    )
}
