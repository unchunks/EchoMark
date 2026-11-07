package com.unchunks.echomark.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.repository.ReadingListRepository
import com.unchunks.echomark.domain.repository.ReviewRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 詳細画面のメニューの「あとで読む」「復習に入れる」(#92・#90)。詳細画面の ViewModel とは別にして、依存を増やさない。
 * 結果は [messages] で知らせる(詳細画面の Snackbar に出す)。
 */
@HiltViewModel
class ReadLaterViewModel @Inject constructor(
    private val readingListRepository: ReadingListRepository,
    private val reviewRepository: ReviewRepository
) : ViewModel() {

    /** 復習に入れているブックマークの ID */
    val reviewIds: StateFlow<Set<Long>> = reviewRepository.observeCards()
        .map { cards -> cards.mapTo(HashSet()) { it.bookmarkId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    /** 「あとで読む」に入れる・外す(入れているかは [Bookmark.isInReadingList])。 */
    fun toggleReadingList(bookmark: Bookmark) = run("操作できませんでした。") {
        if (bookmark.isInReadingList) {
            readingListRepository.remove(bookmark.id)
            messageChannel.trySend("「あとで読む」から外しました")
        } else {
            readingListRepository.add(bookmark.id)
            messageChannel.trySend("「あとで読む」に入れました(読むと自動で外れます)")
        }
    }

    /** 復習に入れる・外す。入れると最初の復習は翌日から。 */
    fun toggleReview(bookmark: Bookmark) = run("操作できませんでした。") {
        if (bookmark.id in reviewIds.value) {
            reviewRepository.unenroll(bookmark.id)
            messageChannel.trySend("復習から外しました")
        } else {
            reviewRepository.enroll(bookmark.id)
            messageChannel.trySend("復習に入れました(最初の復習は明日から)")
        }
    }

    private fun run(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "あとで読む・復習の操作に失敗")
                messageChannel.trySend(failure)
            }
        }
    }
}
