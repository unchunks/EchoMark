package com.unchunks.echomark.ui.subscription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.repository.SubscriptionRepository
import com.unchunks.echomark.domain.subscription.FeedCheckSummary
import com.unchunks.echomark.domain.subscription.SubscribeOutcome
import com.unchunks.echomark.domain.subscription.Subscription
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** 購読の画面でいま行っている操作 */
enum class SubscriptionOperation {
    Idle,

    /** フィードを探して追加している */
    Adding,

    /** 新着を確認している */
    Checking
}

/** 購読の画面の状態。[message] は操作の結果(閉じるまで出す) */
data class SubscriptionsUiState(
    val subscriptions: List<Subscription> = emptyList(),
    val operation: SubscriptionOperation = SubscriptionOperation.Idle,
    val message: String? = null
) {
    val isBusy: Boolean get() = operation != SubscriptionOperation.Idle
}

/** 購読するフィード(RSS・Atom・YouTube)の追加・オン/オフ・削除・いま確認する、の操作。 */
@HiltViewModel
class SubscriptionViewModel @Inject constructor(
    private val repository: SubscriptionRepository
) : ViewModel() {

    private val operation = MutableStateFlow(SubscriptionOperation.Idle)
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SubscriptionsUiState> =
        combine(repository.observeAll(), operation, message) { subscriptions, operation, message ->
            SubscriptionsUiState(subscriptions, operation, message)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubscriptionsUiState())

    /**
     * [input](サイト・フィード・YouTube のチャンネルの URL)の購読を追加し、すぐに最新の記事を確認する。
     * 追加できたら [onAdded] を呼ぶ(入力欄を空にするため)。
     */
    fun add(input: String, onAdded: () -> Unit = {}) {
        if (operation.value != SubscriptionOperation.Idle) return
        operation.value = SubscriptionOperation.Adding
        message.value = null
        viewModelScope.launch {
            try {
                when (val outcome = repository.subscribe(input)) {
                    is SubscribeOutcome.Added -> {
                        onAdded()
                        operation.value = SubscriptionOperation.Checking
                        val summary = repository.checkNow(outcome.id)
                        message.value = addedMessage(outcome.title, summary)
                    }
                    is SubscribeOutcome.Duplicate -> message.value = "「${outcome.title}」はすでに購読しています"
                    is SubscribeOutcome.Failed -> message.value = outcome.message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "購読の追加に失敗")
                message.value = "追加できませんでした。もう一度お試しください"
            } finally {
                operation.value = SubscriptionOperation.Idle
            }
        }
    }

    /** 有効なすべてのフィードを、いま確認する(夜間の確認とは別に、利用者が押したとき) */
    fun checkNow() {
        if (operation.value != SubscriptionOperation.Idle) return
        operation.value = SubscriptionOperation.Checking
        message.value = null
        viewModelScope.launch {
            try {
                message.value = checkedMessage(repository.checkNow())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "フィードの確認に失敗")
                message.value = "確認できませんでした。もう一度お試しください"
            } finally {
                operation.value = SubscriptionOperation.Idle
            }
        }
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(id, enabled) }
    }

    /** 購読をやめる(保存済みのブックマークは残る) */
    fun remove(id: Long) {
        viewModelScope.launch { repository.remove(id) }
    }

    fun dismissMessage() {
        message.value = null
    }

    internal companion object {
        fun addedMessage(title: String, summary: FeedCheckSummary): String = when {
            summary.failed > 0 -> "「$title」を購読しました。最新の記事は、あとで確認します"
            summary.added > 0 -> "「$title」を購読しました。最新の ${summary.added} 件を保存しました。これからの新着は毎晩確認します"
            else -> "「$title」を購読しました。これからの新着は毎晩確認します"
        }

        fun checkedMessage(summary: FeedCheckSummary): String = when {
            summary.checked == 0 -> "確認するフィードがありません"
            summary.added > 0 && summary.failed > 0 -> "${summary.added} 件を保存しました(${summary.failed} 件のフィードは確認できませんでした)"
            summary.added > 0 -> "${summary.added} 件の新着を保存しました"
            summary.failed > 0 -> "${summary.failed} 件のフィードを確認できませんでした"
            else -> "新着はありませんでした"
        }
    }
}
