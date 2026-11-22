package com.unchunks.echomark.ui.podcast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.components.composeui.ReadableLazyColumn
import com.unchunks.echomark.domain.podcast.EpisodeTimeline
import com.unchunks.echomark.domain.podcast.PodcastFormat
import com.unchunks.echomark.domain.podcast.PodcastPlayback
import com.unchunks.echomark.domain.podcast.PodcastSpeaker
import com.unchunks.echomark.ui.components.ErrorState
import com.unchunks.echomark.ui.components.LoadingState

/** 再生の画面の操作。状態を持たない [PodcastPlayerContent] に渡す */
class PodcastPlayerCallbacks(
    val onBack: () -> Unit = {},
    val onTogglePlay: () -> Unit = {},
    val onSeekTo: (Long) -> Unit = {},
    val onSeekBy: (Long) -> Unit = {},
    val onSpeedChange: (Float) -> Unit = {},
    val onPlayLine: (Int) -> Unit = {},
    val onPlaySource: (Int) -> Unit = {},
    val onOpenBookmark: (Long) -> Unit = {}
)

/** ポッドキャストの再生。画面を離れても再生は続く(通知から操作できる)。ViewModel をつなぐだけの薄いラッパー */
@Composable
fun PodcastPlayerScreen(
    onBack: () -> Unit,
    onOpenBookmark: (Long) -> Unit,
    viewModel: PodcastPlayerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    PodcastPlayerContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        callbacks = PodcastPlayerCallbacks(
            onBack = onBack,
            onTogglePlay = viewModel::togglePlay,
            onSeekTo = viewModel::seekTo,
            onSeekBy = viewModel::seekBy,
            onSpeedChange = viewModel::setSpeed,
            onPlayLine = viewModel::playLine,
            onPlaySource = viewModel::playSource,
            onOpenBookmark = onOpenBookmark
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastPlayerContent(
    uiState: PodcastPlayerUiState,
    snackbarHostState: SnackbarHostState,
    callbacks: PodcastPlayerCallbacks,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(uiState.episode?.title ?: "ポッドキャスト", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (uiState.episode != null && uiState.errorMessage == null) PlayerControls(uiState, callbacks)
        }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            val episode = uiState.episode
            when {
                uiState.isLoading -> LoadingState(message = "読み込み中…")
                uiState.errorMessage != null || episode == null -> ErrorState(message = uiState.errorMessage ?: "ポッドキャストを読み込めませんでした。")
                else -> {
                    val listState = rememberLazyListState()
                    val headerItems = 2 + episode.sources.size
                    // 読んでいる行を見える位置に保つ(利用者がスクロールしている間は動かさない)
                    LaunchedEffect(uiState.currentLine) {
                        val line = uiState.currentLine ?: return@LaunchedEffect
                        if (!listState.isScrollInProgress) listState.animateScrollToItem((headerItems + line - 1).coerceAtLeast(0))
                    }
                    ReadableLazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        item(key = "sources-title") { SectionTitle("出典(${episode.sources.size}件)", Modifier.padding(vertical = 8.dp)) }
                        itemsIndexed(episode.sources, key = { index, _ -> "source-$index" }) { index, source ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${index + 1}. ${source.title}", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    source.sourceName?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                IconButton(onClick = { callbacks.onPlaySource(index + 1) }) {
                                    Icon(Icons.Outlined.PlayCircle, contentDescription = "${index + 1}件目の紹介から聞く")
                                }
                                IconButton(onClick = { callbacks.onOpenBookmark(source.bookmarkId) }) {
                                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "「${source.title}」のブックマークを開く")
                                }
                            }
                        }
                        item(key = "script-title") { SectionTitle("台本", Modifier.padding(top = 16.dp, bottom = 4.dp)) }
                        itemsIndexed(episode.lines, key = { index, _ -> "line-$index" }) { index, line ->
                            val current = index == uiState.currentLine
                            val speaker = when {
                                episode.format == PodcastFormat.SOLO -> null
                                line.speaker == PodcastSpeaker.HOST -> "進行役"
                                else -> "聞き役"
                            }
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable(enabled = line.startMs != null, onClickLabel = "ここから聞く") { callbacks.onPlayLine(index) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .semantics { if (current) stateDescription = "読んでいるところ" }
                            ) {
                                if (speaker != null) {
                                    Text(speaker, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                }
                                Text(
                                    line.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                                    color = if (current) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 位置(つまみ)・時間・戻る/再生/進む・速さ */
@Composable
private fun PlayerControls(uiState: PodcastPlayerUiState, callbacks: PodcastPlayerCallbacks) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            val duration = uiState.durationMs.coerceAtLeast(1)
            // つまみを動かしている間は、その位置を出す(離したところへ移る)
            var dragging by remember { mutableStateOf(false) }
            var dragValue by remember { mutableFloatStateOf(0f) }
            val position = if (dragging) (dragValue * duration).toLong() else uiState.positionMs
            Slider(
                value = (position.toFloat() / duration).coerceIn(0f, 1f),
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    callbacks.onSeekTo((dragValue * duration).toLong())
                    dragging = false
                },
                modifier = Modifier.semantics { contentDescription = "再生の位置" }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(EpisodeTimeline.clock(position), style = MaterialTheme.typography.labelMedium)
                Text(EpisodeTimeline.clock(uiState.durationMs), style = MaterialTheme.typography.labelMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { callbacks.onSeekBy(-PodcastPlayback.SKIP_BACK_MS) }) {
                    Icon(Icons.Filled.Replay10, contentDescription = "${PodcastPlayback.SKIP_BACK_MS / 1_000}秒戻る")
                }
                FilledIconButton(onClick = callbacks.onTogglePlay, modifier = Modifier.padding(horizontal = 24.dp).size(64.dp)) {
                    Icon(
                        if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (uiState.isPlaying) "一時停止" else "再生",
                        modifier = Modifier.size(36.dp)
                    )
                }
                IconButton(onClick = { callbacks.onSeekBy(PodcastPlayback.SKIP_FORWARD_MS) }) {
                    Icon(Icons.Filled.Forward30, contentDescription = "${PodcastPlayback.SKIP_FORWARD_MS / 1_000}秒進む")
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("速さ", style = MaterialTheme.typography.labelMedium)
                PodcastPlayback.SPEEDS.forEach { speed ->
                    FilterChip(
                        selected = uiState.speed == speed,
                        onClick = { callbacks.onSpeedChange(speed) },
                        label = { Text(speedLabel(speed)) }
                    )
                }
            }
        }
    }
}

/** 「1.25倍」 */
internal fun speedLabel(speed: Float): String =
    (if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString().trimEnd('0')) + "倍"
