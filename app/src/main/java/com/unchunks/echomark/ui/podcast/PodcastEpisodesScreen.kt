package com.unchunks.echomark.ui.podcast

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.components.composeui.ReadableLazyColumn
import com.unchunks.echomark.domain.podcast.EpisodeStatus
import com.unchunks.echomark.domain.podcast.PodcastEpisode
import com.unchunks.echomark.domain.podcast.PodcastProgress
import com.unchunks.echomark.domain.podcast.ReadingEntry
import com.unchunks.echomark.domain.podcast.ScriptGenerator
import com.unchunks.echomark.ui.components.EmptyState
import com.unchunks.echomark.ui.components.ErrorState
import com.unchunks.echomark.ui.components.LoadingState
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** エピソードの一覧の操作。状態を持たない [PodcastEpisodesContent] に渡す */
class PodcastEpisodesCallbacks(
    val onBack: () -> Unit = {},
    val onOpenDictionary: () -> Unit = {},
    /** できたエピソードを、アプリの中で聞く(再生の画面を開く) */
    val onPlay: (PodcastEpisode) -> Unit = {},
    /** できたエピソードを、ほかのアプリ(音楽のプレーヤーなど)で聞く */
    val onListen: (PodcastEpisode) -> Unit = {},
    val onRecreate: (PodcastEpisode) -> Unit = {},
    val onDelete: (PodcastEpisode) -> Unit = {},
    val onDeleteOlderThan: (days: Int) -> Unit = {},
    val onDeleteAll: () -> Unit = {},
    /** エピソードで使った読みを直して、辞書に入れる */
    val onSaveReading: (surface: String, reading: String) -> Unit = { _, _ -> }
)

/** ポッドキャストのエピソードの一覧。ViewModel をつなぐだけの薄いラッパー */
@Composable
fun PodcastEpisodesScreen(
    onBack: () -> Unit,
    onOpenDictionary: () -> Unit,
    onPlay: (String) -> Unit = {},
    viewModel: PodcastEpisodesViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            launch {
                snackbarHostState.showSnackbar(
                    when (message) {
                        is PodcastEpisodesMessage.Deleted -> if (message.count == 0) "削除するものはありませんでした" else "${message.count}件を削除しました"
                        is PodcastEpisodesMessage.ReadingSaved -> "「${message.surface}」の読みを辞書に入れました。作り直すと使います"
                        is PodcastEpisodesMessage.Recreating -> "「${message.title}」を作り直しています"
                        is PodcastEpisodesMessage.Failed -> message.message
                    }
                )
            }
        }
    }
    PodcastEpisodesContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        callbacks = PodcastEpisodesCallbacks(
            onBack = onBack,
            onOpenDictionary = onOpenDictionary,
            onPlay = { episode -> onPlay(episode.id) },
            onListen = { episode -> listenWithOtherApp(context, viewModel.audioFileOf(episode)) },
            onRecreate = viewModel::recreate,
            onDelete = viewModel::delete,
            onDeleteOlderThan = viewModel::deleteOlderThan,
            onDeleteAll = viewModel::deleteAll,
            onSaveReading = viewModel::saveReading
        )
    )
}

private const val OLD_DAYS = 30

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastEpisodesContent(
    uiState: PodcastEpisodesUiState,
    snackbarHostState: SnackbarHostState,
    callbacks: PodcastEpisodesCallbacks,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault()
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var editingReading by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("ポッドキャスト", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") }
                },
                actions = {
                    IconButton(onClick = callbacks.onOpenDictionary) { Icon(Icons.Outlined.Spellcheck, contentDescription = "読みの辞書") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "その他のメニュー") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("${OLD_DAYS}日より前のものを削除") },
                                onClick = { menuOpen = false; confirm = CONFIRM_OLD },
                                enabled = uiState.episodes.isNotEmpty()
                            )
                            DropdownMenuItem(
                                text = { Text("すべて削除", color = MaterialTheme.colorScheme.error) },
                                onClick = { menuOpen = false; confirm = CONFIRM_ALL },
                                enabled = uiState.episodes.isNotEmpty()
                            )
                        }
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
                uiState.episodes.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Podcasts,
                    title = "ポッドキャストはまだありません",
                    description = "ブックマークの詳細や、一覧で選んだブックマークの「その他の操作」から作れます。"
                )
                else -> ReadableLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.episodes, key = { it.id }) { episode ->
                        EpisodeCard(
                            episode = episode,
                            progress = uiState.progress[episode.id],
                            expanded = expanded == episode.id,
                            zone = zone,
                            onToggle = { expanded = if (expanded == episode.id) null else episode.id },
                            onPlay = { callbacks.onPlay(episode) },
                            onListen = { callbacks.onListen(episode) },
                            onRecreate = { callbacks.onRecreate(episode) },
                            onDelete = { deleting = episode.id },
                            onEditReading = { editingReading = it }
                        )
                    }
                }
            }
        }
    }

    when (confirm) {
        CONFIRM_OLD -> ConfirmDialog(
            title = "${OLD_DAYS}日より前のものを削除",
            text = "${OLD_DAYS}日より前に作ったエピソードと音声を削除します。元に戻せません。",
            onConfirm = { confirm = null; callbacks.onDeleteOlderThan(OLD_DAYS) },
            onDismiss = { confirm = null }
        )
        CONFIRM_ALL -> ConfirmDialog(
            title = "すべて削除",
            text = "${uiState.episodes.size}件のエピソードと音声を削除します。作っている途中のものも止めます。元に戻せません。",
            onConfirm = { confirm = null; callbacks.onDeleteAll() },
            onDismiss = { confirm = null }
        )
    }
    deleting?.let { id ->
        val episode = uiState.episodes.firstOrNull { it.id == id }
        if (episode == null) {
            deleting = null
        } else {
            ConfirmDialog(
                title = "エピソードを削除",
                text = "「${episode.title}」と音声を削除します。元に戻せません。",
                onConfirm = { deleting = null; callbacks.onDelete(episode) },
                onDismiss = { deleting = null }
            )
        }
    }
    editingReading?.let { surface ->
        val current = uiState.dictionary.firstOrNull { it.matchKey == ReadingEntry.keyOf(surface) }
            ?: uiState.episodes.flatMap { it.readings }.firstOrNull { it.matchKey == ReadingEntry.keyOf(surface) }
        FixReadingDialog(
            surface = surface,
            initialReading = current?.reading.orEmpty(),
            onConfirm = { reading -> editingReading = null; callbacks.onSaveReading(surface, reading) },
            onDismiss = { editingReading = null }
        )
    }
}

private const val CONFIRM_OLD = "old"
private const val CONFIRM_ALL = "all"

@Composable
private fun EpisodeCard(
    episode: PodcastEpisode,
    progress: PodcastProgress?,
    expanded: Boolean,
    zone: ZoneId,
    onToggle: () -> Unit,
    onPlay: () -> Unit,
    onListen: () -> Unit,
    onRecreate: () -> Unit,
    onDelete: () -> Unit,
    onEditReading: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            Modifier.fillMaxWidth().clickable(onClickLabel = if (expanded) "詳しい情報を閉じる" else "詳しい情報を開く", onClick = onToggle).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(episode.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                episodeSummary(episode, zone),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            when (episode.status) {
                EpisodeStatus.GENERATING -> {
                    Text(progress?.label ?: "作る順番を待っています", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                EpisodeStatus.FAILED -> Text(
                    episode.failureReason ?: "作れませんでした。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                EpisodeStatus.READY -> if (episode.generator == ScriptGenerator.SUMMARY_READ) {
                    Text(
                        "AI を使えなかったため、要約を読み上げています。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (expanded) EpisodeDetails(episode, onEditReading, onListen.takeIf { episode.status == EpisodeStatus.READY })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (episode.status == EpisodeStatus.READY) {
                    FilledTonalButton(onClick = onPlay) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Text("聞く", Modifier.padding(start = 4.dp))
                    }
                }
                if (episode.status != EpisodeStatus.GENERATING) {
                    OutlinedButton(onClick = onRecreate) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Text("作り直す", Modifier.padding(start = 4.dp))
                    }
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = null)
                    Text(if (episode.status == EpisodeStatus.GENERATING) "やめて削除" else "削除", Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

/** 出典と、読み上げで使った読み(直して辞書に入れられる) */
@Composable
private fun EpisodeDetails(episode: PodcastEpisode, onEditReading: (String) -> Unit, onListen: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle("出典")
        episode.sources.forEachIndexed { index, source ->
            Text(
                "${index + 1}. ${source.title}" + (source.sourceName?.let { "($it)" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        onListen?.let { listen ->
            TextButton(onClick = listen) { Text("ほかのアプリで聞く") }
        }
        if (episode.readings.isNotEmpty()) {
            SectionTitle("使った読み", Modifier.padding(top = 8.dp))
            episode.readings.forEach { entry ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${entry.surface} → ${entry.reading}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(top = 12.dp)
                    )
                    IconButton(onClick = { onEditReading(entry.surface) }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "「${entry.surface}」の読みを直す")
                    }
                }
            }
        }
    }
}

/** 作った日時・長さ・大きさ */
internal fun episodeSummary(episode: PodcastEpisode, zone: ZoneId): String = buildList {
    add(DATE_FORMAT.withZone(zone).format(Instant.ofEpochMilli(episode.createdAt)))
    add("${episode.sources.size}件")
    add(episode.format.label)
    episode.durationMs?.let { add(durationText(it)) }
    episode.sizeBytes?.let { add(sizeText(it)) }
}.joinToString(" ・ ")

internal fun durationText(ms: Long): String {
    val seconds = (ms + 500) / 1_000
    return if (seconds < 60) "${seconds}秒" else "${seconds / 60}分${(seconds % 60).toString().padStart(2, '0')}秒"
}

internal fun sizeText(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    else -> "${(bytes + 999) / 1_000} KB"
}

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/M/d H:mm")

@Composable
private fun ConfirmDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("削除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

@Composable
private fun FixReadingDialog(surface: String, initialReading: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var reading by rememberSaveable { mutableStateOf(initialReading) }
    val problem = ReadingEntry.problemOf(surface, reading)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「$surface」の読み") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("正しい読みを入れると、読みの辞書に入れます。次に作るときから使います。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = reading,
                    onValueChange = { reading = it },
                    label = { Text("読み") },
                    singleLine = true,
                    supportingText = { problem?.takeIf { reading.isNotBlank() }?.let { Text(it) } },
                    isError = problem != null && reading.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(reading) }, enabled = problem == null) { Text("辞書に入れる") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

/** 音声を、ほかのアプリで再生する(音楽のプレーヤーに入れたいときなど) */
private fun listenWithOtherApp(context: Context, file: File?) {
    if (file == null) {
        Toast.makeText(context, "音声のファイルがありません", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (e: IllegalArgumentException) {
        Timber.w(e, "ポッドキャストの音声の URI を作れない")
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "audio/wav")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "音声を再生できるアプリがありません", Toast.LENGTH_SHORT).show()
    }
}
