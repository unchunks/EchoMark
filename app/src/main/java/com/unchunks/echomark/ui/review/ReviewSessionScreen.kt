package com.unchunks.echomark.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.review.ReviewGrade
import com.unchunks.echomark.ui.components.EmptyState
import com.unchunks.echomark.ui.components.ErrorState
import com.unchunks.echomark.ui.components.LoadingState
import com.unchunks.echomark.ui.components.TagChip
import com.unchunks.echomark.ui.components.displaySource

/** 復習の答えのボタンの名前 */
private fun ReviewGrade.label(): String = when (this) {
    ReviewGrade.AGAIN -> "もう一度"
    ReviewGrade.HARD -> "むずかしい"
    ReviewGrade.GOOD -> "思い出せた"
    ReviewGrade.EASY -> "かんたん"
}

/**
 * 復習(#90)。前面にタイトルだけを出し、思い出してから「答えを見る」で要約を見て、思い出せたかを 4 段階で答える。
 * ViewModel をつなぐだけの薄いラッパー。
 */
@Composable
fun ReviewSessionScreen(
    onBack: () -> Unit,
    onOpenBookmark: (Long) -> Unit,
    viewModel: ReviewSessionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    ReviewSessionContent(
        uiState = uiState,
        onBack = onBack,
        onOpenBookmark = onOpenBookmark,
        onReveal = viewModel::reveal,
        onAnswer = viewModel::answer
    )
}

/** 画面の本体(状態を受け取って描くだけ)。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewSessionContent(
    uiState: ReviewSessionUiState,
    onBack: () -> Unit,
    onOpenBookmark: (Long) -> Unit,
    onReveal: () -> Unit,
    onAnswer: (ReviewGrade) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("復習", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            val current = uiState.current
            when {
                uiState.isLoading -> LoadingState()
                uiState.errorMessage != null && current == null -> ErrorState(message = uiState.errorMessage)
                uiState.isFinished -> FinishedView(uiState.answered, onBack)
                current != null -> CardView(uiState, current.bookmark, onOpenBookmark, onReveal, onAnswer)
            }
        }
    }
}

@Composable
private fun FinishedView(answered: Int, onBack: () -> Unit) {
    EmptyState(
        icon = Icons.Outlined.TaskAlt,
        title = if (answered == 0) "いま復習できるものはありません" else "復習が終わりました",
        description = if (answered == 0) "次の復習の日になると、ここに出ます。" else "${answered}回、答えました。次の復習は、それぞれの間隔のあとに出ます。",
        actionLabel = "戻る",
        onAction = onBack
    )
}

@Composable
private fun CardView(
    state: ReviewSessionUiState,
    bookmark: Bookmark,
    onOpenBookmark: (Long) -> Unit,
    onReveal: () -> Unit,
    onAnswer: (ReviewGrade) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val done = (state.total - state.queue.size).coerceAtLeast(0)
        Text("残り ${state.queue.size}件", style = MaterialTheme.typography.labelLarge)
        LinearProgressIndicator(
            progress = { if (state.total == 0) 0f else done.toFloat() / state.total },
            modifier = Modifier.fillMaxWidth()
        )
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(bookmark.displaySource(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(bookmark.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                if (!state.showAnswer) {
                    Text(
                        "この内容を思い出せますか?\n頭の中で要点を思い出してから、答えを見てください。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val answer = bookmark.summary?.takeIf { it.isNotBlank() }
                        ?: bookmark.content?.takeIf { it.isNotBlank() }?.take(MAX_ANSWER_CHARS)
                    Text(answer ?: "要約がありません。元のブックマークを開いて確かめてください。", style = MaterialTheme.typography.bodyLarge)
                    if (bookmark.tags.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            bookmark.tags.take(4).forEach { TagChip(name = it) }
                        }
                    }
                    TextButton(onClick = { onOpenBookmark(bookmark.id) }) { Text("元のブックマークを開く") }
                }
            }
        }
        if (!state.showAnswer) {
            Button(onClick = onReveal, modifier = Modifier.fillMaxWidth()) { Text("答えを見る") }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReviewGrade.entries.forEach { grade ->
                    OutlinedButton(
                        onClick = { onAnswer(grade) },
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(grade.label(), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            Text(
                                state.intervalLabels[grade].orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 要約が無いときに答えとして出す本文の長さ(文字) */
private const val MAX_ANSWER_CHARS = 400
