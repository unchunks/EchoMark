package com.unchunks.echomark.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Delete
import com.unchunks.echomark.domain.factcheck.FactCheckTarget
import com.unchunks.echomark.ui.factcheck.FactCheckSheet
import com.unchunks.echomark.ui.factcheck.FactCheckViewModel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkCollection
import com.unchunks.echomark.domain.bookmark.model.BookmarkNote
import com.unchunks.echomark.domain.bookmark.model.CollectionResult
import com.unchunks.echomark.domain.export.BookmarkExporter
import com.unchunks.echomark.domain.export.ExportFormat
import com.unchunks.echomark.ui.collections.exportedText
import com.unchunks.echomark.domain.bookmark.model.ReadingBlocks
import com.unchunks.echomark.domain.bookmark.model.ReadingStyle
import com.unchunks.echomark.domain.bookmark.model.readingMinutes
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.model.SummaryLanguage
import com.unchunks.echomark.ui.common.openAttachment
import com.unchunks.echomark.ui.common.openUrl
import com.unchunks.echomark.ui.common.shareBookmark
import com.unchunks.echomark.ui.snapshot.PageSnapshotCallbacks
import com.unchunks.echomark.ui.snapshot.PageSnapshotDialog
import com.unchunks.echomark.ui.snapshot.PageSnapshotViewModel
import com.unchunks.echomark.ui.video.VideoOfflineCallbacks
import com.unchunks.echomark.ui.video.VideoOfflineUiState
import com.unchunks.echomark.ui.video.VideoOfflineViewModel
import com.unchunks.echomark.ui.video.VideoPlayerActivity
import com.unchunks.echomark.ui.places.PlaceCallbacks
import com.unchunks.echomark.ui.places.PlaceEditViewModel
import com.unchunks.echomark.ui.places.openInMapApp
import com.unchunks.echomark.ui.shortcut.PinnedShortcuts
import com.unchunks.echomark.ui.review.ReadLaterViewModel
import com.unchunks.echomark.ui.snapshot.SnapshotMessage
import com.unchunks.echomark.ui.snapshot.SnapshotOperation
import com.unchunks.echomark.ui.snapshot.SnapshotViewerActivity
import com.unchunks.echomark.ui.snapshot.openSnapshotPdf
import com.unchunks.echomark.ui.components.EmptyState
import com.unchunks.echomark.ui.components.LoadingState
import com.unchunks.echomark.ui.components.description
import com.unchunks.echomark.ui.components.message
import com.unchunks.echomark.ui.components.displaySource
import kotlinx.coroutines.launch

/**
 * ブックマークの詳細。ViewModel をつなぐだけの薄いラッパーで、描画は [BookmarkDetailContent] が行う。
 *
 * @param onAskAi このブックマークについて AI に質問する(チャットの新規会話を開く)
 * @param onMakePodcast このブックマークからポッドキャストを作る(作る画面を開く)
 * @param onOpenAiSettings AI の準備ができていないときに、AI 設定を開く
 * @param showBackButton トップバーに戻るボタンを出すか。2 画面表示の右側では出さない
 *   ([onBack] は削除後や「見つかりません」の「戻る」で、右側を空にするのに使われる)
 */
@Composable
fun BookmarkDetailScreen(
    onBack: () -> Unit,
    onOpenBookmark: (Long) -> Unit,
    onAskAi: (Long) -> Unit = {},
    onMakePodcast: (Long) -> Unit = {},
    onOpenAiSettings: () -> Unit = {},
    showBackButton: Boolean = true,
    viewModel: BookmarkDetailViewModel = hiltViewModel(),
    snapshotViewModel: PageSnapshotViewModel = hiltViewModel(),
    placeViewModel: PlaceEditViewModel = hiltViewModel(),
    readLaterViewModel: ReadLaterViewModel = hiltViewModel(),
    factCheckViewModel: FactCheckViewModel = hiltViewModel(),
    videoOfflineViewModel: VideoOfflineViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val factCheck by factCheckViewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val snapshotOperation by snapshotViewModel.operation.collectAsState()
    val reviewIds by readLaterViewModel.reviewIds.collectAsState()
    var confirmRemoveSnapshot by rememberSaveable { mutableStateOf(false) }
    val videoOffline by videoOfflineViewModel.uiState.collectAsState()
    var confirmRemoveVideoOffline by rememberSaveable { mutableStateOf(false) }
    var confirmRemoveVideoFile by rememberSaveable { mutableStateOf(false) }

    // 動画のオフライン保存の結果(保存した・やめた・消した・失敗)
    LaunchedEffect(videoOfflineViewModel, snackbarHostState) {
        videoOfflineViewModel.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            launch { snackbarHostState.showSnackbar(message) }
        }
    }

    // 「あとで読む」「復習に入れる」の結果
    LaunchedEffect(readLaterViewModel, snackbarHostState) {
        readLaterViewModel.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            launch { snackbarHostState.showSnackbar(message) }
        }
    }

    // オフラインコピーの結果(保存した・消した・失敗)
    LaunchedEffect(snapshotViewModel, snackbarHostState) {
        snapshotViewModel.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            launch {
                snackbarHostState.showSnackbar(
                    when (message) {
                        is SnapshotMessage.Saved ->
                            if (message.hasPdf) "オフラインコピーを保存しました" else "オフラインコピーを保存しました(PDF は作れませんでした)"
                        SnapshotMessage.Removed -> "オフラインコピーを削除しました"
                        is SnapshotMessage.Failed -> message.message
                    }
                )
            }
        }
    }

    // 書き出し先を選ぶ画面は MIME タイプごとに作る(形式ごとに用意)
    val markdownExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.MARKDOWN.mimeType)) { uri ->
        if (uri != null) viewModel.exportBookmark(ExportFormat.MARKDOWN, uri.toString())
    }
    val htmlExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.BROWSER_HTML.mimeType)) { uri ->
        if (uri != null) viewModel.exportBookmark(ExportFormat.BROWSER_HTML, uri.toString())
    }

    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            launch {
                when (message) {
                    is BookmarkDetailMessage.ArchiveChanged -> {
                        val result = snackbarHostState.showSnackbar(
                            message = if (message.archived) "アーカイブしました" else "アーカイブから戻しました",
                            actionLabel = "元に戻す",
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            viewModel.setArchived(!message.archived, notify = false)
                        }
                    }
                    BookmarkDetailMessage.Edited -> {
                        val result = snackbarHostState.showSnackbar(
                            message = "保存しました",
                            actionLabel = "AIで要約し直す",
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.reprocessAfterEdit()
                    }
                    BookmarkDetailMessage.SummaryEdited -> snackbarHostState.showSnackbar("要約を保存しました。前の要約は履歴に残っています")
                    BookmarkDetailMessage.SummaryRestored -> snackbarHostState.showSnackbar("要約を戻しました")
                    is BookmarkDetailMessage.NoteDeleted -> {
                        val result = snackbarHostState.showSnackbar(
                            message = "ノートを削除しました",
                            actionLabel = "元に戻す",
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.restoreNote(message.note)
                    }
                    is BookmarkDetailMessage.CollectionChanged ->
                        snackbarHostState.showSnackbar(
                            if (message.added) "コレクション「${message.name}」に入れました" else "コレクション「${message.name}」から外しました"
                        )
                    is BookmarkDetailMessage.LinkChecked -> snackbarHostState.showSnackbar(linkCheckedText(message.state))
                    is BookmarkDetailMessage.Exported ->
                        snackbarHostState.showSnackbar(exportedText(message.result.exported, message.result.skipped))
                    is BookmarkDetailMessage.TagRemoved -> {
                        val result = snackbarHostState.showSnackbar(
                            message = "タグ「${message.name}」を外しました",
                            actionLabel = "元に戻す",
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.addTag(message.name)
                    }
                    BookmarkDetailMessage.ReprocessStarted -> snackbarHostState.showSnackbar("AI で処理し直しています…")
                    is BookmarkDetailMessage.Failed -> snackbarHostState.showSnackbar(message.message)
                }
            }
        }
    }

    BookmarkDetailContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        callbacks = BookmarkDetailCallbacks(
            onBack = onBack,
            onOpenBookmark = onOpenBookmark,
            onOpenInBrowser = { url -> openUrl(context, url) },
            onOpenFile = { openAttachment(context, it) },
            onPlayAttachmentVideo = { bookmark ->
                bookmark.filePath?.let { context.startActivity(VideoPlayerActivity.intent(context, it, bookmark.title)) }
            },
            onAskAi = onAskAi,
            onMakePodcast = onMakePodcast,
            onOpenAiSettings = onOpenAiSettings,
            onShare = { shareBookmark(context, it) },
            onPinShortcut = { PinnedShortcuts.request(context, PinnedShortcuts.forBookmark(it)) },
            readLater = ReadLaterCallbacks(
                isInReview = uiState.bookmark?.id in reviewIds,
                onToggleReadingList = readLaterViewModel::toggleReadingList,
                onToggleReview = readLaterViewModel::toggleReview
            ),
            onToggleFavorite = viewModel::toggleFavorite,
            onToggleRead = viewModel::toggleRead,
            onReadingStyleChange = viewModel::setReadingStyle,
            onSetArchived = { viewModel.setArchived(it) },
            onReprocess = { viewModel.reprocess() },
            onReprocessPart = viewModel::reprocess,
            onDelete = { viewModel.delete(onDeleted = onBack) },
            onSaveEdit = viewModel::saveEdit,
            onEditSummary = viewModel::editSummary,
            onSelectCategory = viewModel::setCategory,
            onResetCategory = viewModel::resetCategory,
            onRestoreSummary = viewModel::restoreSummary,
            onFactCheck = { summary -> uiState.bookmark?.let { factCheckViewModel.open(FactCheckTarget.bookmark(it.id), summary) } },
            onTranslateSummary = viewModel::translateSummary,
            onClearTranslation = viewModel::clearTranslation,
            onAddTag = viewModel::addTag,
            onRemoveTag = viewModel::removeTag,
            onAddNote = viewModel::addNote,
            onUpdateNote = viewModel::updateNote,
            onDeleteNote = viewModel::deleteNote,
            onSetInCollection = viewModel::setInCollection,
            onCreateCollection = viewModel::createCollectionAndAdd,
            onRecheckLink = viewModel::recheckLink,
            onMuteLink = viewModel::muteLinkCheck,
            onExport = { format ->
                val fileName = BookmarkExporter.fileName(uiState.bookmark?.title.orEmpty(), format)
                when (format) {
                    ExportFormat.MARKDOWN -> markdownExportLauncher.launch(fileName)
                    ExportFormat.BROWSER_HTML -> htmlExportLauncher.launch(fileName)
                }
            },
            snapshot = PageSnapshotCallbacks(
                onSave = { uiState.bookmark?.let(snapshotViewModel::start) },
                onOpen = { uiState.bookmark?.let { context.startActivity(SnapshotViewerActivity.intent(context, it.id)) } },
                onOpenPdf = { uiState.bookmark?.let { openSnapshotPdf(context, it) } },
                onRemove = { confirmRemoveSnapshot = true }
            ),
            place = PlaceCallbacks(
                onSave = { name, point -> uiState.bookmark?.let { placeViewModel.save(it.id, name, point) } },
                onClear = { uiState.bookmark?.let { placeViewModel.clear(it.id) } },
                fetchCurrentLocation = placeViewModel::currentLocation,
                onOpenInMap = { point, title -> openInMapApp(context, point, title) }
            ),
            snapshotBusy = snapshotOperation != SnapshotOperation.Idle,
            videoOffline = VideoOfflineCallbacks(
                onSave = videoOfflineViewModel::save,
                onCancel = videoOfflineViewModel::cancel,
                onRemove = { confirmRemoveVideoOffline = true },
                onDownloadVideo = videoOfflineViewModel::downloadVideo,
                onCancelDownload = videoOfflineViewModel::cancelDownload,
                onPlayVideo = {
                    videoOffline.saved?.video?.path?.let { path ->
                        context.startActivity(VideoPlayerActivity.intent(context, path, uiState.bookmark?.title))
                    }
                },
                onRemoveVideo = { confirmRemoveVideoFile = true }
            ),
            videoOfflineState = videoOffline
        ),
        showBackButton = showBackButton
    )

    if (confirmRemoveVideoOffline) {
        AlertDialog(
            onDismissRequest = { confirmRemoveVideoOffline = false },
            title = { Text("オフライン用の保存を削除しますか?") },
            text = { Text("端末に保存したサムネイルと動画を削除します。字幕と要約はブックマークに残ります。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemoveVideoOffline = false
                        videoOfflineViewModel.remove()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveVideoOffline = false }) { Text("キャンセル") } }
        )
    }

    if (confirmRemoveVideoFile) {
        AlertDialog(
            onDismissRequest = { confirmRemoveVideoFile = false },
            title = { Text("保存した動画を削除しますか?") },
            text = { Text("端末に保存した動画のファイルを削除します。字幕・要約・サムネイルは残ります。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemoveVideoFile = false
                        videoOfflineViewModel.removeVideo()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveVideoFile = false }) { Text("キャンセル") } }
        )
    }

    FactCheckSheet(
        state = factCheck,
        onCheck = factCheckViewModel::check,
        onOpenUrl = { url -> openUrl(context, url) },
        onOpenAiSettings = {
            factCheckViewModel.close()
            onOpenAiSettings()
        },
        onDismiss = factCheckViewModel::close
    )

    // ページを読み込んでオフラインコピーを作っている間の画面
    (snapshotOperation as? SnapshotOperation.Capturing)?.let { capturing ->
        PageSnapshotDialog(
            url = capturing.url,
            onCaptured = { snapshotViewModel.onCaptured(it.mhtml, it.pdf) },
            onFailed = snapshotViewModel::onFailed,
            onCancel = snapshotViewModel::cancel,
            isOffline = snapshotViewModel::isOffline
        )
    }

    if (confirmRemoveSnapshot) {
        AlertDialog(
            onDismissRequest = { confirmRemoveSnapshot = false },
            title = { Text("オフラインコピーを削除しますか?") },
            text = { Text("端末に保存したページと PDF を削除します。ブックマーク自体は残ります。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemoveSnapshot = false
                        uiState.bookmark?.let { snapshotViewModel.remove(it.id) }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveSnapshot = false }) { Text("キャンセル") } }
        )
    }
}

/** 詳細画面の操作。状態を持たない [BookmarkDetailContent] に渡す */
class BookmarkDetailCallbacks(
    val onBack: () -> Unit = {},
    val onOpenBookmark: (Long) -> Unit = {},
    val onOpenInBrowser: (String) -> Unit = {},
    /** 保存したファイルをほかのアプリで開く */
    val onOpenFile: (Bookmark) -> Unit = {},
    /** 保存した動画のファイルを、アプリの中で再生する */
    val onPlayAttachmentVideo: (Bookmark) -> Unit = {},
    val onAskAi: (Long) -> Unit = {},
    /** このブックマークからポッドキャストを作る */
    val onMakePodcast: (Long) -> Unit = {},
    val onOpenAiSettings: () -> Unit = {},
    val onShare: (Bookmark) -> Unit = {},
    /** ホーム画面にショートカットを置く */
    val onPinShortcut: (Bookmark) -> Unit = {},
    /** 「あとで読む」(#92)・復習(#90)に入れる・外す */
    val readLater: ReadLaterCallbacks = ReadLaterCallbacks(),
    val onToggleFavorite: () -> Unit = {},
    /** 読んだことにする / 未読に戻す */
    val onToggleRead: () -> Unit = {},
    /** 読書モードの文字の大きさ・背景・書体を変える */
    val onReadingStyleChange: (ReadingStyle) -> Unit = {},
    val onSetArchived: (Boolean) -> Unit = {},
    val onReprocess: () -> Unit = {},
    /** 要約だけ・タグだけを作り直す */
    val onReprocessPart: (AnalysisScope) -> Unit = {},
    val onDelete: () -> Unit = {},
    val onSaveEdit: (title: String, content: String) -> Unit = { _, _ -> },
    /** 要約を手で直す(#19) */
    val onEditSummary: (String) -> Unit = {},
    /** カテゴリを選ぶ(自分で選んだものになる) */
    val onSelectCategory: (String) -> Unit = {},
    /** カテゴリを AI に任せる状態に戻す */
    val onResetCategory: () -> Unit = {},
    /** 履歴の版(ID)に要約を戻す(#20) */
    val onRestoreSummary: (Long) -> Unit = {},
    /** 要約(引数)を Web で確かめるシートを開く。要約は書き換えない */
    val onFactCheck: (String) -> Unit = {},
    /** 要約を [SummaryLanguage] に翻訳する(#24) */
    val onTranslateSummary: (SummaryLanguage) -> Unit = {},
    /** 翻訳を閉じる */
    val onClearTranslation: () -> Unit = {},
    val onAddTag: (String) -> Unit = {},
    val onRemoveTag: (String) -> Unit = {},
    /** ノート(注釈)を足す・直す・消す(#95)。第2引数は引用した文章 */
    val onAddNote: (text: String, quote: String?) -> Unit = { _, _ -> },
    val onUpdateNote: (id: Long, text: String, quote: String?) -> Unit = { _, _, _ -> },
    val onDeleteNote: (BookmarkNote) -> Unit = {},
    /** コレクションに入れる・外す、新しく作って入れる(#91) */
    val onSetInCollection: (BookmarkCollection, Boolean) -> Unit = { _, _ -> },
    val onCreateCollection: (name: String, onResult: (CollectionResult) -> Unit) -> Unit = { _, _ -> },
    /** このブックマークをファイルに書き出す(保存先を選ぶ画面を開く) */
    val onExport: (ExportFormat) -> Unit = {},
    /** リンク切れの確認(#94): 今すぐ確認し直す / この夜の確認をやめる */
    val onRecheckLink: () -> Unit = {},
    val onMuteLink: () -> Unit = {},
    /** ページのオフラインコピー(#80)の操作 */
    val snapshot: PageSnapshotCallbacks = PageSnapshotCallbacks(),
    /** 場所(#85)の操作 */
    val place: PlaceCallbacks = PlaceCallbacks(),
    /** オフラインコピーを作っている(または保存している)最中 */
    val snapshotBusy: Boolean = false,
    /** 動画のオフライン保存(字幕・要約・サムネイル)の操作と状態 */
    val videoOffline: VideoOfflineCallbacks = VideoOfflineCallbacks(),
    val videoOfflineState: VideoOfflineUiState = VideoOfflineUiState()
)

/**
 * 詳細画面の本体(状態を受け取って描くだけ)。
 * 上から: ファイルのプレビュー(または OG 画像) → 出どころと保存日時 → タイトル → ファイルの情報 → 開く/質問ボタン →
 * AI 要約(または AI の状態) → タグ → 本文/メモ(ファイルから読み取った文字を含む) → 関連。
 * 広い画面では、本文が横に間延びしないよう中央の読みやすい幅に収める(スクロールは画面の幅全体で受ける)。
 *
 * @param showBackButton トップバーに戻るボタンを出すか(2 画面表示の右側では出さない)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkDetailContent(
    uiState: BookmarkDetailUiState,
    snackbarHostState: SnackbarHostState,
    callbacks: BookmarkDetailCallbacks,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
    showBackButton: Boolean = true
) {
    val bookmark = uiState.bookmark
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showEditSheet by rememberSaveable { mutableStateOf(false) }
    var readingMode by rememberSaveable { mutableStateOf(false) }
    // ノートの編集シート。開いているか・直すノートの ID(新規なら null)・引用する文章(読書モードの段落の長押しなど)
    var showCollectionSheet by rememberSaveable { mutableStateOf(false) }
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    var noteEditorOpen by rememberSaveable { mutableStateOf(false) }
    var noteEditorId by rememberSaveable { mutableStateOf<Long?>(null) }
    var noteEditorQuote by rememberSaveable { mutableStateOf<String?>(null) }
    val openNoteEditor: (BookmarkNote?, String?) -> Unit = { note, quote ->
        noteEditorId = note?.id
        noteEditorQuote = note?.quote ?: quote
        noteEditorOpen = true
    }
    val readingBlocks = remember(bookmark?.content) { ReadingBlocks.from(bookmark?.content) }
    // 本文が無い(または消えた)なら、読書モードのままにしない
    val inReadingMode = readingMode && bookmark != null && readingBlocks.isNotEmpty()

    if (inReadingMode && bookmark != null) {
        ReadingModeContent(
            title = bookmark.title,
            source = bookmark.displaySource(),
            readingMinutes = remember(bookmark.content) { bookmark.readingMinutes() },
            blocks = readingBlocks,
            style = uiState.readingStyle,
            highlightTerms = uiState.highlightTerms,
            onStyleChange = callbacks.onReadingStyleChange,
            onClose = { readingMode = false },
            // 段落を長押しすると、閉じてその文章を引用したノートの編集シートを開く
            onQuote = { quote ->
                readingMode = false
                openNoteEditor(null, quote)
            },
            modifier = modifier
        )
    } else {
        Scaffold(
            modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                DetailTopBar(
                    bookmark = bookmark,
                    scrollBehavior = scrollBehavior,
                    callbacks = callbacks,
                    showBackButton = showBackButton,
                    onEdit = { showEditSheet = true },
                    onDelete = { showDeleteDialog = true },
                    onReadingMode = if (readingBlocks.isNotEmpty()) ({ readingMode = true }) else null,
                    onAddToCollection = { showCollectionSheet = true },
                    onExport = { showExportDialog = true }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { innerPadding ->
            when {
                uiState.isLoading -> LoadingState(Modifier.padding(innerPadding), message = "読み込み中…")
                bookmark == null -> EmptyState(
                    icon = Icons.Outlined.BookmarkRemove,
                    title = "ブックマークが見つかりません",
                    description = "削除された可能性があります。",
                    actionLabel = "戻る",
                    onAction = callbacks.onBack,
                    modifier = Modifier.padding(innerPadding)
                )
                else -> DetailBody(
                    bookmark = bookmark,
                    uiState = uiState,
                    callbacks = callbacks,
                    nowMillis = nowMillis,
                    onOpenNoteEditor = openNoteEditor,
                    contentPadding = innerPadding
                )
            }
        }
    }

    if (showDeleteDialog && bookmark != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text("削除しますか?") },
            text = { Text("「${bookmark.title}」を削除します。一覧に戻ったあと、しばらくは元に戻せます。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        callbacks.onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("キャンセル") }
            }
        )
    }

    if (showCollectionSheet && bookmark != null) {
        AddToCollectionSheet(
            collections = uiState.collections,
            memberOf = uiState.collectionIds,
            onSetInCollection = callbacks.onSetInCollection,
            onCreate = callbacks.onCreateCollection,
            onDismiss = { showCollectionSheet = false }
        )
    }
    if (showExportDialog && bookmark != null) {
        ExportFormatDialog(
            onSelect = { format ->
                showExportDialog = false
                callbacks.onExport(format)
            },
            onDismiss = { showExportDialog = false }
        )
    }

    val editingNote = uiState.notes.firstOrNull { it.id == noteEditorId }
    // 直そうとしたノートが消えていた(別の画面で削除したなど)ときは、シートを出さない
    if (noteEditorOpen && bookmark != null && (noteEditorId == null || editingNote != null)) {
        val editing = editingNote
        NoteEditorSheet(
            noteId = editing?.id,
            initialText = editing?.text.orEmpty(),
            initialQuote = if (editing != null) editing.quote else noteEditorQuote,
            onDismiss = { noteEditorOpen = false },
            onSave = { text, quote ->
                noteEditorOpen = false
                if (editing != null) callbacks.onUpdateNote(editing.id, text, quote) else callbacks.onAddNote(text, quote)
            }
        )
    }

    if (showEditSheet && bookmark != null) {
        EditBookmarkSheet(
            bookmark = bookmark,
            onDismiss = { showEditSheet = false },
            onSave = { title, content ->
                showEditSheet = false
                callbacks.onSaveEdit(title, content)
            }
        )
    }
}

/** 「もう一度確認」の結果を知らせる文言 */
internal fun linkCheckedText(state: com.unchunks.echomark.domain.linkcheck.LinkCheckState?): String = when {
    state == null -> "確認できませんでした。通信できるか確かめてください"
    state.failureCount == 0 -> "リンクは開けました"
    state.isDead -> "まだ開けません"
    else -> "今回は開けませんでした。続けて開けないときに、リンク切れとしてお知らせします"
}

/** 詳細画面のメニューの「あとで読む」「復習に入れる」の操作。[isInReview] は復習に入れているか。 */
class ReadLaterCallbacks(
    val isInReview: Boolean = false,
    val onToggleReadingList: (Bookmark) -> Unit = {},
    val onToggleReview: (Bookmark) -> Unit = {}
)
