package com.unchunks.echomark.ui.review

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.components.composeui.ReadableLazyColumn
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.ReadingTime
import com.unchunks.echomark.domain.bookmark.model.readingMinutes
import com.unchunks.echomark.domain.review.SpacedRepetition
import com.unchunks.echomark.domain.review.WeeklyReview
import com.unchunks.echomark.ui.components.CompactBookmarkRow
import com.unchunks.echomark.ui.components.EmptyState
import com.unchunks.echomark.ui.components.ErrorState
import com.unchunks.echomark.ui.components.LoadingState
import com.unchunks.echomark.ui.components.TagChip
import kotlinx.coroutines.launch
import java.time.ZoneId

/** ふり返りの 3 つの表示 */
enum class ReviewHubTab(val label: String) {
    WEEKLY("今週のふり返り"),
    READING_LIST("あとで読む"),
    REVIEW("復習")
}

/** ふり返りの操作(状態を持たない画面に渡す) */
class ReviewHubActions(
    val onBack: () -> Unit = {},
    val onOpenBookmark: (Long) -> Unit = {},
    val onStartReview: () -> Unit = {},
    val onAddToReadingList: (Bookmark) -> Unit = {},
    val onRemoveFromReadingList: (Bookmark) -> Unit = {},
    val onMarkRead: (Bookmark) -> Unit = {},
    val onRemoveFromReview: (Bookmark) -> Unit = {}
)

/**
 * ふり返り(今週のふり返り・あとで読む・復習)。ViewModel をつなぐだけの薄いラッパー。
 * @param initialTab 開く表示(通知からは今週のふり返り)
 */
@Composable
fun ReviewHubScreen(
    onBack: () -> Unit,
    onOpenBookmark: (Long) -> Unit,
    onStartReview: () -> Unit,
    initialTab: ReviewHubTab = ReviewHubTab.WEEKLY,
    viewModel: ReviewHubViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            launch {
                when (message) {
                    is ReviewHubMessage.RemovedFromReadingList -> {
                        val result = snackbarHostState.showSnackbar(
                            "「${message.title.take(20)}」を「あとで読む」から外しました", "元に戻す", duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.undoRemoveFromReadingList(message.bookmarkId)
                    }
                    is ReviewHubMessage.MarkedRead -> snackbarHostState.showSnackbar("「${message.title.take(20)}」を読んだことにしました")
                    is ReviewHubMessage.RemovedFromReview -> {
                        val result = snackbarHostState.showSnackbar(
                            "「${message.title.take(20)}」を復習から外しました", "元に戻す", duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.undoRemoveFromReview(message.bookmarkId)
                    }
                    is ReviewHubMessage.Failed -> snackbarHostState.showSnackbar(message.message)
                }
            }
        }
    }
    ReviewHubContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        actions = ReviewHubActions(
            onBack = onBack,
            onOpenBookmark = onOpenBookmark,
            onStartReview = onStartReview,
            onAddToReadingList = viewModel::addToReadingList,
            onRemoveFromReadingList = viewModel::removeFromReadingList,
            onMarkRead = viewModel::markRead,
            onRemoveFromReview = viewModel::removeFromReview
        ),
        initialTab = initialTab
    )
}

/** 画面の本体(状態を受け取って描くだけ)。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewHubContent(
    uiState: ReviewHubUiState,
    snackbarHostState: SnackbarHostState,
    actions: ReviewHubActions,
    modifier: Modifier = Modifier,
    initialTab: ReviewHubTab = ReviewHubTab.WEEKLY,
    nowMillis: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault()
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("読む・ふり返る", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReviewHubTab.entries.forEach { entry ->
                    FilterChip(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        label = { Text(tabLabel(entry, uiState)) }
                    )
                }
            }
            when {
                uiState.isLoading -> LoadingState()
                uiState.errorMessage != null -> ErrorState(message = uiState.errorMessage)
                else -> ReadableLazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    when (tab) {
                        ReviewHubTab.WEEKLY -> weeklyItems(uiState, actions)
                        ReviewHubTab.READING_LIST -> readingListItems(uiState, actions)
                        ReviewHubTab.REVIEW -> reviewItems(uiState, actions, nowMillis, zone)
                    }
                }
            }
        }
    }
}

private fun tabLabel(tab: ReviewHubTab, state: ReviewHubUiState): String = when (tab) {
    ReviewHubTab.WEEKLY -> tab.label
    ReviewHubTab.READING_LIST -> if (state.readingList.items.isEmpty()) tab.label else "${tab.label} ${state.readingList.items.size}"
    ReviewHubTab.REVIEW -> if (state.dueCount == 0) tab.label else "${tab.label} ${state.dueCount}"
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall
    )
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// ---- 今週のふり返り ----

/** 「12件(前の7日間は 8件)」。前の期間と同じなら件数だけ。 */
private fun withPrevious(count: Int, previous: Int): String =
    if (previous == count) "${count}件" else "${count}件(前の7日間は ${previous}件)"

private fun minutesText(minutes: Int): String = when {
    minutes <= 0 -> "—"
    minutes < 60 -> "約${minutes}分"
    minutes % 60 == 0 -> "約${minutes / 60}時間"
    else -> "約${minutes / 60}時間${minutes % 60}分"
}

private fun LazyListScope.weeklyItems(state: ReviewHubUiState, actions: ReviewHubActions) {
    val review = state.weekly ?: return
    item(key = "weekly-summary") { WeeklySummary(review, actions) }
    if (review.topTags.isNotEmpty()) {
        item(key = "weekly-tags") {
            Column {
                SectionTitle("よく付いたタグ")
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    review.topTags.forEach { tag -> TagChip(name = "${tag.label} ${tag.count}") }
                }
                if (review.newTags.isNotEmpty()) {
                    Note("初めて付いたタグ: " + review.newTags.take(8).joinToString("、"))
                }
            }
        }
    }
    if (review.recentSaved.isNotEmpty()) {
        item(key = "weekly-saved-title") { SectionTitle("この7日間に保存したもの") }
        items(review.recentSaved, key = { "saved-${it.id}" }) { bookmark ->
            CompactBookmarkRow(
                bookmark = bookmark,
                onClick = { actions.onOpenBookmark(bookmark.id) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }
    }
    if (review.oldestUnread.isNotEmpty()) {
        item(key = "weekly-stale-title") {
            Column {
                SectionTitle("読まずに放ってあるもの")
                Note("保存して7日以上たっても、まだ読んでいないものです。「あとで読む」に入れると、読み忘れません。")
            }
        }
        items(review.oldestUnread, key = { "stale-${it.id}" }) { bookmark ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactBookmarkRow(
                    bookmark = bookmark,
                    onClick = { actions.onOpenBookmark(bookmark.id) },
                    modifier = Modifier.weight(1f).padding(start = 12.dp, top = 2.dp, bottom = 2.dp)
                )
                if (!bookmark.isInReadingList) {
                    TextButton(onClick = { actions.onAddToReadingList(bookmark) }) { Text("あとで読む") }
                }
            }
        }
    }
}

@Composable
private fun WeeklySummary(review: WeeklyReview, actions: ReviewHubActions) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (review.isQuiet) {
            Text(
                "この7日間は、保存も読み終えたものもありませんでした。",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        SummaryRow("保存した", withPrevious(review.savedCount, review.savedPrevious))
        SummaryRow("読んだ", withPrevious(review.readCount, review.readPrevious))
        SummaryRow("読んだ時間(目安)", minutesText(review.readingMinutes))
        SummaryRow("まだ読んでいない", "${review.unreadBacklog}件")
        SummaryRow("あとで読む", "${review.readingListCount}件")
        SummaryRow("復習できる", "${review.dueReviews}件")
        if (review.dueReviews > 0) {
            FilledTonalButton(onClick = actions.onStartReview) {
                Icon(Icons.Outlined.Quiz, contentDescription = null)
                Text("復習を始める", modifier = Modifier.padding(start = 8.dp))
            }
        }
        Note("「この7日間」は、いまから7日さかのぼった期間です。読んだ時間は、本文から出した目安(本文が無いものは入れていません)。")
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
    HorizontalDivider()
}

// ---- あとで読む ----

private fun LazyListScope.readingListItems(state: ReviewHubUiState, actions: ReviewHubActions) {
    val list = state.readingList
    if (list.isEmpty) {
        item(key = "reading-empty") {
            EmptyState(
                icon = Icons.Outlined.AutoStories,
                title = "「あとで読む」はまだ空です",
                description = "ブックマークの詳細画面のメニューから「あとで読む」に入れると、ここに入れた順に並びます。読むと自動で外れます。",
                modifier = Modifier.height(280.dp)
            )
        }
        return
    }
    item(key = "reading-header") {
        Column {
            list.totalLabel()?.let { Note(it) }
            list.next?.let { next ->
                FilledTonalButton(
                    onClick = { actions.onOpenBookmark(next.id) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                ) { Text("次を読む: ${next.title.take(18)}") }
            }
        }
    }
    items(list.items, key = { "reading-${it.id}" }) { bookmark ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            Column(Modifier.weight(1f)) {
                CompactBookmarkRow(bookmark = bookmark, onClick = { actions.onOpenBookmark(bookmark.id) }, modifier = Modifier.padding(vertical = 2.dp))
                bookmark.readingMinutes()?.let {
                    Text(
                        ReadingTime.label(it),
                        modifier = Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = { actions.onMarkRead(bookmark) }) {
                Icon(Icons.Outlined.Check, contentDescription = "「${bookmark.title}」を読んだことにする")
            }
            IconButton(onClick = { actions.onRemoveFromReadingList(bookmark) }) {
                Icon(Icons.Outlined.Close, contentDescription = "「${bookmark.title}」を「あとで読む」から外す")
            }
        }
    }
}

// ---- 復習 ----

private fun LazyListScope.reviewItems(state: ReviewHubUiState, actions: ReviewHubActions, nowMillis: Long, zone: ZoneId) {
    item(key = "review-header") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "間隔をあけて思い出す復習です。内容を思い出せたかを答えるほど、次に出るまでの間隔が延びます。" +
                    "詳細画面のメニューの「復習に入れる」で、復習したいブックマークを選びます(自動では入りません)。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "復習できる ${state.dueCount}件 / 復習に入れている ${state.reviewCards.size}件",
                style = MaterialTheme.typography.bodyLarge
            )
            FilledTonalButton(onClick = actions.onStartReview, enabled = state.dueCount > 0) {
                Icon(Icons.Outlined.Quiz, contentDescription = null)
                Text(if (state.dueCount > 0) "復習を始める" else "いま復習できるものはありません", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    if (state.reviewCards.isEmpty()) {
        item(key = "review-empty") {
            EmptyState(
                icon = Icons.Outlined.Quiz,
                title = "復習に入れたブックマークはありません",
                description = "覚えておきたいブックマークの詳細画面で、メニューの「復習に入れる」を選んでください。",
                modifier = Modifier.height(280.dp)
            )
        }
        return
    }
    items(state.reviewCards, key = { "review-${it.bookmark.id}" }) { item ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            Column(Modifier.weight(1f)) {
                CompactBookmarkRow(
                    bookmark = item.bookmark,
                    onClick = { actions.onOpenBookmark(item.bookmark.id) },
                    modifier = Modifier.padding(vertical = 2.dp)
                )
                Text(
                    SpacedRepetition.dueLabel(item.card.dueAt, nowMillis, zone) + "・思い出せた ${item.card.repetitions}回・忘れた ${item.card.lapses}回",
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { actions.onRemoveFromReview(item.bookmark) }) {
                Icon(Icons.Outlined.Close, contentDescription = "「${item.bookmark.title}」を復習から外す")
            }
        }
    }
}
