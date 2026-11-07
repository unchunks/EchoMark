package com.unchunks.echomark.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.insights.InterestTrend
import com.unchunks.echomark.domain.insights.InterestTrends
import com.unchunks.echomark.domain.insights.LibraryStats
import com.unchunks.echomark.domain.insights.LibraryStatsCalculator
import com.unchunks.echomark.domain.insights.TagCloud
import com.unchunks.echomark.domain.insights.TagCloudResult
import com.unchunks.echomark.domain.insights.Timeline
import com.unchunks.echomark.domain.insights.TimelineBasis
import com.unchunks.echomark.domain.insights.TimelineRow
import com.unchunks.echomark.domain.model.TagWithCount
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.TagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.time.ZoneId
import javax.inject.Inject

/** 「関心の変化」で見る期間(月数)の選択肢 */
val INTEREST_PERIOD_CHOICES = listOf(3, 6, 12)

data class InsightsUiState(
    val isLoading: Boolean = true,
    val bookmarkCount: Int = 0,
    val tagCloud: TagCloudResult = TagCloudResult(emptyList(), 0),
    val stats: LibraryStats = LibraryStats.EMPTY,
    val timeline: List<TimelineRow> = emptyList(),
    val timelineBasis: TimelineBasis = TimelineBasis.SAVED,
    val interests: InterestTrend? = null,
    val interestMonths: Int = InterestTrends.DEFAULT_MONTHS,
    val errorMessage: String? = null
)

/**
 * ライブラリの分析(タグクラウド・統計・タイムライン・関心の変化)の状態。
 * 集計はブックマークが数千件あっても画面が止まらないよう、バックグラウンドの Dispatcher で行う。
 */
@HiltViewModel
class InsightsViewModel @Inject constructor(
    bookmarkRepository: BookmarkRepository,
    tagRepository: TagRepository,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    /** 現在時刻(テストで差し替える) */
    internal var clock: () -> Long = System::currentTimeMillis

    /** 月・日の区切りに使うタイムゾーン(テストで差し替える) */
    internal var zone: ZoneId = ZoneId.systemDefault()

    private val timelineBasis = MutableStateFlow(TimelineBasis.SAVED)
    private val interestMonths = MutableStateFlow(InterestTrends.DEFAULT_MONTHS)

    val uiState: StateFlow<InsightsUiState> = combine(
        bookmarkRepository.observeBookmarks(),
        tagRepository.observeTagsWithCount(),
        timelineBasis,
        interestMonths
    ) { bookmarks, tags, basis, months -> build(bookmarks, tags, basis, months) }
        .flowOn(dispatchers.default)
        .catch { e ->
            if (e is CancellationException) throw e
            Timber.w(e, "ライブラリの分析に失敗")
            emit(InsightsUiState(isLoading = false, errorMessage = "ライブラリを読み込めませんでした。"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState())

    private fun build(
        bookmarks: List<Bookmark>,
        tags: List<TagWithCount>,
        basis: TimelineBasis,
        months: Int
    ): InsightsUiState {
        val now = clock()
        return InsightsUiState(
            isLoading = false,
            bookmarkCount = bookmarks.size,
            tagCloud = TagCloud.build(tags),
            stats = LibraryStatsCalculator.compute(bookmarks, now, zone),
            timeline = Timeline.rows(bookmarks, basis, zone),
            timelineBasis = basis,
            interests = InterestTrends.compute(bookmarks, now, zone, months),
            interestMonths = months
        )
    }

    fun setTimelineBasis(basis: TimelineBasis) = timelineBasis.update { basis }

    fun setInterestMonths(months: Int) = interestMonths.update { months }
}
