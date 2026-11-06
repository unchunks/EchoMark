package com.unchunks.echomark.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.ReviewRepository
import com.unchunks.echomark.domain.review.ReviewCard
import com.unchunks.echomark.domain.review.ReviewGrade
import com.unchunks.echomark.domain.review.SpacedRepetition
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.ZoneId
import javax.inject.Inject

/** 復習で出す 1 件(カードとブックマーク)。 */
data class ReviewItem(val card: ReviewCard, val bookmark: Bookmark)

data class ReviewSessionUiState(
    val isLoading: Boolean = true,
    /** これから出すもの(先頭が今出しているもの) */
    val queue: List<ReviewItem> = emptyList(),
    /** 答えを見ているか(前面はタイトルだけ、答えを見ると要約が出る) */
    val showAnswer: Boolean = false,
    /** この回に答えた回数(「もう一度」で出し直したものも数える) */
    val answered: Int = 0,
    /** この回に最初に出した件数 */
    val total: Int = 0,
    /** 各答えのあとの次の復習の言い方(「明日」「3日後」…)。今出しているものの分 */
    val intervalLabels: Map<ReviewGrade, String> = emptyMap(),
    val errorMessage: String? = null
) {
    val current: ReviewItem? get() = queue.firstOrNull()
    val isFinished: Boolean get() = !isLoading && queue.isEmpty()
}

/**
 * 復習(#90)。日時が来ているカードを [SpacedRepetition.SESSION_LIMIT] 件まで集め、前面(タイトル)→ 答え(要約)→ 4 段階の答え、の順に進める。
 * 「もう一度」はこの回の最後に出し直す(次の日時は 10 分後として保存する)。ほかの答えはその場で保存し、この回からは外す。
 */
@HiltViewModel
class ReviewSessionViewModel @Inject constructor(
    private val reviewRepository: ReviewRepository,
    private val bookmarkRepository: BookmarkRepository
) : ViewModel() {

    /** 現在時刻・次の復習の日にちの区切り(テストで差し替える) */
    internal var clock: () -> Long = System::currentTimeMillis
    internal var zone: ZoneId = ZoneId.systemDefault()

    private val _uiState = MutableStateFlow(ReviewSessionUiState())
    val uiState: StateFlow<ReviewSessionUiState> = _uiState.asStateFlow()

    /** 答えの保存中は、続けて押された答えを受け付けない */
    private var busy = false

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val cards = reviewRepository.dueCards(SpacedRepetition.SESSION_LIMIT)
                val bookmarks = bookmarkRepository.getBookmarksByIds(cards.map { it.bookmarkId }).associateBy { it.id }
                // ブックマークが削除されていたカードは出さない(カードは連鎖して消えるので、通常は残らない)
                val items = cards.mapNotNull { card -> bookmarks[card.bookmarkId]?.let { ReviewItem(card, it) } }
                _uiState.update { withLabels(it.copy(isLoading = false, queue = items, total = items.size)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "復習のカードを読み込めなかった")
                _uiState.update { it.copy(isLoading = false, errorMessage = "復習を読み込めませんでした。") }
            }
        }
    }

    /** 答えを見る */
    fun reveal() = _uiState.update { it.copy(showAnswer = true) }

    /** 今出しているものに [grade] で答える。 */
    fun answer(grade: ReviewGrade) {
        val item = _uiState.value.current ?: return
        if (busy || !_uiState.value.showAnswer) return
        busy = true
        viewModelScope.launch {
            try {
                val updated = reviewRepository.grade(item.bookmark.id, grade)
                _uiState.update { state ->
                    val rest = state.queue.drop(1)
                    // 「もう一度」は、この回の最後に出し直す。カードが無くなっていた(別の画面で外した)ときは出さない
                    val queue = if (grade == ReviewGrade.AGAIN && updated != null) rest + item.copy(card = updated) else rest
                    withLabels(state.copy(queue = queue, showAnswer = false, answered = state.answered + 1))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "復習の答えを保存できなかった")
                _uiState.update { it.copy(errorMessage = "答えを保存できませんでした。") }
            } finally {
                busy = false
            }
        }
    }

    fun dismissError() = _uiState.update { it.copy(errorMessage = null) }

    /** 今出しているものの、各答えのあとの次の復習の言い方を付ける。 */
    private fun withLabels(state: ReviewSessionUiState): ReviewSessionUiState {
        val card = state.current?.card ?: return state.copy(intervalLabels = emptyMap())
        val now = clock()
        return state.copy(
            intervalLabels = ReviewGrade.entries.associateWith { grade ->
                SpacedRepetition.intervalLabel(SpacedRepetition.next(card, grade, now, zone))
            }
        )
    }
}
