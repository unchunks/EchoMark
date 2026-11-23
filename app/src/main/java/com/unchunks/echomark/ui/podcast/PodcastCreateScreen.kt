package com.unchunks.echomark.ui.podcast

import com.unchunks.echomark.ui.design.designSegmentColors
import com.unchunks.echomark.ui.design.designSegmentShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.components.composeui.ReadableLazyColumn
import com.unchunks.echomark.domain.podcast.PodcastFormat
import com.unchunks.echomark.domain.podcast.PodcastTemplates
import com.unchunks.echomark.ui.components.ErrorState
import com.unchunks.echomark.ui.components.LoadingState
import kotlinx.coroutines.launch
import com.unchunks.echomark.ui.design.EchoMarkButton
import com.unchunks.echomark.ui.design.EchoMarkTopAppBar

/**
 * 選んだブックマークからポッドキャストを作る画面(詳細画面・複数選択から開く)。話し方を選んで作り始めると、
 * エピソードの一覧に移る(作るのは裏で続き、できたら通知で知らせる)。ViewModel をつなぐだけの薄いラッパー。
 */
@Composable
fun PodcastCreateScreen(
    onClose: () -> Unit,
    onStarted: () -> Unit,
    viewModel: PodcastCreateViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.events.collect { event ->
            when (event) {
                is PodcastCreateEvent.Started -> onStarted()
                is PodcastCreateEvent.Failed -> launch { snackbarHostState.showSnackbar(event.message) }
            }
        }
    }
    PodcastCreateContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onClose = onClose,
        onFormatChange = viewModel::setFormat,
        onCreate = viewModel::create
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastCreateContent(
    uiState: PodcastCreateUiState,
    snackbarHostState: SnackbarHostState,
    onClose: () -> Unit,
    onFormatChange: (PodcastFormat) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            EchoMarkTopAppBar(
                title = { Text("ポッドキャストを作る", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "閉じる") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!uiState.isLoading && uiState.errorMessage == null) {
                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    EchoMarkButton(
                        onClick = onCreate,
                        enabled = !uiState.isCreating,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (uiState.isCreating) {
                            CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Podcasts, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        }
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("作る")
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            when {
                uiState.isLoading -> LoadingState(message = "読み込み中…")
                uiState.errorMessage != null -> ErrorState(message = uiState.errorMessage)
                else -> ReadableLazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    item(key = "format") {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionTitle("話し方")
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                PodcastFormat.entries.forEachIndexed { index, format ->
                                    SegmentedButton(
                                        selected = uiState.format == format,
                                        onClick = { onFormatChange(format) },
                                        shape = designSegmentShape(index, PodcastFormat.entries.size),
                                        colors = designSegmentColors()
                                    ) { Text(format.label, maxLines = 1) }
                                }
                            }
                            Text(
                                when (uiState.format) {
                                    PodcastFormat.SOLO -> "語り手が1人で、要点を紹介します。"
                                    PodcastFormat.DUO -> "進行役と聞き役の2人が、掛け合いで紹介します。声の数が足りない端末では、同じ声の高さを変えます。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    item(key = "sources-title") {
                        SectionTitle("紹介するブックマーク(${uiState.titles.size}件)", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                    itemsIndexed(uiState.titles, key = { index, _ -> "source-$index" }) { index, title ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
                            Text("${index + 1}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(28.dp))
                            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (uiState.skipped > 0) {
                        item(key = "skipped") {
                            Text(
                                "1回にまとめられるのは${PodcastTemplates.MAX_SOURCES}件までです。残りの${uiState.skipped}件は入れません。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }
                    }
                    item(key = "about") {
                        Text(
                            "AI が要約と本文から台本を書き、端末の読み上げで音声にします(読み上げは端末の中だけで行います)。" +
                                "AI を使えないときは、要約をそのまま読みます。読み間違えた言葉は、読みの辞書で直せます。" +
                                "端末内の AI では、1件あたり1分ほどかかることがあります。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.semantics { heading() }
    )
}
