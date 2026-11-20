package com.unchunks.echomark.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.ReadingListRepository
import com.unchunks.echomark.domain.repository.ReviewRepository
import com.unchunks.echomark.domain.review.ReadingList
import com.unchunks.echomark.domain.review.ReviewCard
import com.unchunks.echomark.domain.review.SpacedRepetition
import com.unchunks.echomark.domain.review.WeeklyReview
import com.unchunks.echomark.domain.review.WeeklyReviewCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** 復習に入れているブックマーク1件(次の復習までの言い方つき)。 */
data class ReviewCardItem(val card: ReviewCard, val bookmark: Bookmark, val due: Boolean)

data class ReviewHubUiState(
    val isLoading: Boolean = true,
    val weekly: WeeklyReview? = null,
    val readingList: ReadingList = ReadingList.EMPTY,
    /** 復習に入れているもの(次の復習が早い順。ブックマークが無いカードは除く) */
    val reviewCards: List<ReviewCardItem> = emptyList(),
    val dueCount: Int = 0,
    val errorMessage: String? = null
)

/** ふり返りの画面で一度だけ伝える出来事(Snackbar で知らせる)。 */
sealed interface ReviewHubMessage {
    /** 「あとで読む」から外した。[bookmarkId] を入れ直して元に戻せる */
    data class RemovedFromReadingList(val title: String, val bookmarkId: Long) : ReviewHubMessage

    /** 読んだことにした(「あとで読む」からも外れた) */
    data class MarkedRead(val title: String) : ReviewHubMessage

    data class RemovedFromReview(val title: String, val bookmarkId: Long) : ReviewHubMessage

    data class Failed(val message: String) : ReviewHubMessage
}

/**
 * ふり返り(今週のふり返り・あとで読む・復習)。ブックマークと復習のカードをまとめて集計する(数千件でも止まらないよう、
 * [DispatcherProvider.default] で行う)。
 */
@HiltViewModel
class ReviewHubViewModel @Inject constructor(
    private val bookmarkRepository: BookmarkRepository,
    private val readingListRepository: ReadingListRepository,
    private val reviewRepository: ReviewRepository,
    dispatchers: DispatcherProvider
) : ViewModel() {

    /** 現在時刻(テストで差し替える) */
    internal var clock: () -> Long = System::currentTimeMillis

    private val messageChannel = Channel<ReviewHubMessage>(Channel.BUFFERED)
    val messages: Flow<ReviewHubMessage> = messageChannel.receiveAsFlow()

    val uiState: StateFlow<ReviewHubUiState> = combine(
        bookmarkRepository.observeBookmarks(),
        reviewRepository.observeCards()
    ) { bookmarks, cards ->
        val now = clock()
        val byId = bookmarks.associateBy { it.id }
        val due = SpacedRepetition.due(cards, now).mapTo(HashSet()) { it.bookmarkId }
        ReviewHubUiState(
            isLoading = false,
            weekly = WeeklyReviewCalculator.compute(bookmarks, now, dueReviews = due.count { it in byId }),
            readingList = ReadingList.of(bookmarks),
            reviewCards = cards.mapNotNull { card -> byId[card.bookmarkId]?.let { ReviewCardItem(card, it, card.bookmarkId in due) } },
            dueCount = due.count { it in byId }
        )
    }
        .flowOn(dispatchers.default)
        .catch { e ->
            if (e is CancellationException) throw e
            Timber.w(e, "ふり返りの集計に失敗")
            emit(ReviewHubUiState(isLoading = false, errorMessage = "読み込めませんでした。"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewHubUiState())

    /** 「あとで読む」に入れる(読まずに放ってあるものから入れるときなど) */
    fun addToReadingList(bookmark: Bookmark) = run { readingListRepository.add(bookmark.id) }

    fun removeFromReadingList(bookmark: Bookmark) = run {
        readingListRepository.remove(bookmark.id)
        messageChannel.trySend(ReviewHubMessage.RemovedFromReadingList(bookmark.title, bookmark.id))
    }

    /** 外したのを元に戻す(入れた日時は入れ直した時点になる) */
    fun undoRemoveFromReadingList(bookmarkId: Long) = run { readingListRepository.add(bookmarkId) }

    /** 読んだことにする(「あとで読む」からも外れる) */
    fun markRead(bookmark: Bookmark) = run {
        bookmarkRepository.setRead(bookmark.id, true)
        messageChannel.trySend(ReviewHubMessage.MarkedRead(bookmark.title))
    }

    fun removeFromReview(bookmark: Bookmark) = run {
        reviewRepository.unenroll(bookmark.id)
        messageChannel.trySend(ReviewHubMessage.RemovedFromReview(bookmark.title, bookmark.id))
    }

    fun undoRemoveFromReview(bookmarkId: Long) = run { reviewRepository.enroll(bookmarkId) }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "ふり返りの操作に失敗")
                messageChannel.trySend(ReviewHubMessage.Failed("操作できませんでした。"))
            }
        }
    }
}
