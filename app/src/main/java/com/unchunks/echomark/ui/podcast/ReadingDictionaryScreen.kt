package com.unchunks.echomark.ui.podcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.components.composeui.ReadableLazyColumn
import com.unchunks.echomark.domain.podcast.ReadingEntry
import com.unchunks.echomark.ui.components.ErrorState
import com.unchunks.echomark.ui.components.LoadingState
import kotlinx.coroutines.launch

/**
 * ポッドキャストの読みの辞書(表記 → 読み)。読み上げの直前に、AI の読みより先に当てる。ViewModel をつなぐだけの薄いラッパー。
 */
@Composable
fun ReadingDictionaryScreen(
    onBack: () -> Unit,
    viewModel: ReadingDictionaryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            launch {
                when (message) {
                    is ReadingDictionaryMessage.Saved -> snackbarHostState.showSnackbar("「${message.surface}」の読みを保存しました")
                    is ReadingDictionaryMessage.Failed -> snackbarHostState.showSnackbar(message.message)
                    is ReadingDictionaryMessage.Removed -> {
                        val result = snackbarHostState.showSnackbar("「${message.entry.surface}」の読みを削除しました", actionLabel = "元に戻す")
                        if (result == SnackbarResult.ActionPerformed) viewModel.restore(message.entry)
                    }
                }
            }
        }
    }

    ReadingDictionaryContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onSave = viewModel::save,
        onRemove = viewModel::remove,
        onSampleChange = viewModel::setSample
    )
}

/** 読みの辞書の画面の本体(状態を受け取って描くだけ)。入れる・直すダイアログもここで出す */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingDictionaryContent(
    uiState: ReadingDictionaryUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSave: (surface: String, reading: String, original: String?) -> Unit,
    onRemove: (ReadingEntry) -> Unit,
    onSampleChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var adding by rememberSaveable { mutableStateOf(false) }
    // 直している表記(null なら閉じている)
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("読みの辞書", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { adding = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "読みを入れる")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            when {
                uiState.isLoading -> LoadingState(message = "読み込み中…")
                uiState.errorMessage != null -> ErrorState(message = uiState.errorMessage)
                else -> ReadableLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    item(key = "about") { AboutCard() }
                    item(key = "sample") { SampleCard(uiState.sample, uiState.samplePreview, onSampleChange) }
                    item(key = "header") {
                        Text(
                            "入れた読み(${uiState.entries.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() }
                        )
                    }
                    if (uiState.entries.isEmpty()) {
                        item(key = "empty") {
                            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "まだありません。読み間違えた言葉の表記と読みを入れると、次から正しく読みます。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(onClick = { adding = true }) { Text("読みを入れる") }
                            }
                        }
                    }
                    items(uiState.entries, key = { it.matchKey }) { entry ->
                        ListItem(
                            headlineContent = { Text(entry.surface, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(entry.reading, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { editing = entry.surface }) {
                                        Icon(Icons.Outlined.Edit, contentDescription = "「${entry.surface}」の読みを直す")
                                    }
                                    IconButton(onClick = { onRemove(entry) }) {
                                        Icon(Icons.Outlined.Delete, contentDescription = "「${entry.surface}」の読みを削除")
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (adding) {
        ReadingEditDialog(
            title = "読みを入れる",
            initial = null,
            existing = uiState.entries,
            onDismiss = { adding = false },
            onConfirm = { surface, reading ->
                adding = false
                onSave(surface, reading, null)
            }
        )
    }
    editing?.let { surface ->
        val entry = uiState.entries.firstOrNull { it.surface == surface }
        if (entry == null) {
            editing = null
        } else {
            ReadingEditDialog(
                title = "読みを直す",
                initial = entry,
                existing = uiState.entries,
                onDismiss = { editing = null },
                onConfirm = { newSurface, reading ->
                    editing = null
                    onSave(newSurface, reading, entry.surface)
                }
            )
        }
    }
}

@Composable
private fun AboutCard() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ポッドキャストの読み上げで、表記を読みに置き換えます。", style = MaterialTheme.typography.bodyMedium)
            Text(
                "人名・地名・製品名など、読み間違えやすい言葉を入れてください。英字の表記は大文字・小文字を区別しません。" +
                    "ここに入れた読みは、AI が見つけた読みより先に使います。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 文を入れると、読み上げに渡す形(辞書・数字・記号を整えたもの)を見せる */
@Composable
private fun SampleCard(sample: String, preview: String, onChange: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "読みを確かめる",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() }
        )
        OutlinedTextField(
            value = sample,
            onValueChange = onChange,
            label = { Text("確かめる文") },
            placeholder = { Text("例: 2026/11/20 10:30 に GitHub で v2.1 を公開") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        if (preview.isNotEmpty()) {
            Text("読み上げる文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(preview, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * 1件を入れる・直すダイアログ。表記が空・長すぎる・読みが表記と同じなら保存できない。
 * 同じ表記がすでにあれば、置き換えることを知らせる(直している1件は除く)。
 */
@Composable
private fun ReadingEditDialog(
    title: String,
    initial: ReadingEntry?,
    existing: List<ReadingEntry>,
    onDismiss: () -> Unit,
    onConfirm: (surface: String, reading: String) -> Unit
) {
    var surface by rememberSaveable { mutableStateOf(initial?.surface.orEmpty()) }
    var reading by rememberSaveable { mutableStateOf(initial?.reading.orEmpty()) }
    val problem = ReadingEntry.problemOf(surface, reading)
    val replaces = surface.isNotBlank() && existing.any {
        it.matchKey == ReadingEntry.keyOf(surface) && it.matchKey != initial?.matchKey
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = surface,
                    onValueChange = { surface = it },
                    label = { Text("表記") },
                    placeholder = { Text("例: 日本橋") },
                    singleLine = true,
                    supportingText = {
                        Text(if (replaces) "同じ表記の読みを置き換えます" else "${surface.trim().length}/${ReadingEntry.MAX_SURFACE_CHARS}文字")
                    },
                    isError = surface.trim().length > ReadingEntry.MAX_SURFACE_CHARS,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = reading,
                    onValueChange = { reading = it },
                    label = { Text("読み") },
                    placeholder = { Text("例: にほんばし") },
                    singleLine = true,
                    supportingText = {
                        Text(problem?.takeIf { surface.isNotBlank() && reading.isNotBlank() } ?: "ひらがな・カタカナで入れると確実です")
                    },
                    isError = problem != null && surface.isNotBlank() && reading.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(surface, reading) }, enabled = problem == null) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}
