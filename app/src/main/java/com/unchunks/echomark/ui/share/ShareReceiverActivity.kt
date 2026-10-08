package com.unchunks.echomark.ui.share

import android.content.ContentResolver
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.aspectRatio
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import com.unchunks.echomark.MainUiState
import com.unchunks.echomark.MainViewModel
import com.unchunks.echomark.isDark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.ui.common.SelectedFile
import com.unchunks.echomark.ui.common.extractDomain
import com.unchunks.echomark.ui.common.formatFileSize
import com.unchunks.echomark.ui.common.querySelectedFile
import com.unchunks.echomark.ui.components.SelectedFileRow
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay

/**
 * 他アプリの共有メニューから受け取ってクイック保存するActivity。
 * テキスト・URL(ACTION_SEND, text/plain)と、ファイル(画像・PDF・音声・動画・テキストファイル。
 * ACTION_SEND / ACTION_SEND_MULTIPLE の EXTRA_STREAM)を受け付ける。
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    private val viewModel: ShareViewModel by viewModels()

    /** テーマ設定の読み込み(アプリ本体と同じ) */
    private val appViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // ファイル名・サイズ・種類を提供元に問い合わせる(プレビューと保存前の確認に使う)
        val shared = parseSharedContent(intent)?.withFileDetails(contentResolver)
        if (shared == null) {
            Toast.makeText(this, "保存できる内容がありません", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            // アプリのテーマ設定(ライト/ダーク・ダイナミックカラー)に合わせる。読み込み前(ほんの一瞬)は何も描かない
            val appState by appViewModel.uiState.collectAsState()
            val ready = appState as? MainUiState.Ready ?: return@setContent
            val darkTheme = ready.themeMode.isDark(isSystemInDarkTheme())
            // ナビゲーションバーのアイコン色も、端末ではなくアプリのテーマに合わせる
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme }
                )
                onDispose {}
            }
            EchoMarkTheme(darkTheme = darkTheme, dynamicColor = ready.dynamicColor) {
                val status by viewModel.status.collectAsState()
                // 保存できたら、シート内で結果を短く見せてから閉じる
                LaunchedEffect(status) {
                    if (status is ShareSaveStatus.Saved) {
                        // 保存できなかったファイルがあるときは、読めるよう長めに見せる
                        delay(if ((status as ShareSaveStatus.Saved).failedCount > 0) PARTIAL_SAVED_DISPLAY_MILLIS else SAVED_DISPLAY_MILLIS)
                        finish()
                    }
                }
                QuickSaveSheet(
                    shared = shared,
                    status = status,
                    onSave = { title, memo, tags -> viewModel.save(shared, title, memo, tags) },
                    onDismiss = { finish() }
                )
            }
        }
    }

    private companion object {
        const val SAVED_DISPLAY_MILLIS = 1_200L
        const val PARTIAL_SAVED_DISPLAY_MILLIS = 3_000L
    }
}

/**
 * 共有インテントから取り出した内容。[files] があればファイルとして保存する([text] は添えられた文章)。
 * ファイルが無く url が null ならテキストとして扱う
 */
data class SharedContent(
    val url: String?,
    val text: String,
    val tentativeTitle: String,
    val files: List<SelectedFile> = emptyList()
)

/** ファイルの名前・サイズ・種類を提供元に問い合わせて埋める */
private fun SharedContent.withFileDetails(resolver: ContentResolver): SharedContent =
    if (files.isEmpty()) this else copy(files = files.map { querySelectedFile(resolver, it.uri.toUri(), it.mimeType) })

private val URL_REGEX = Regex("""https?://[^\s<>"']+""")
private const val TRAILING_PUNCTUATION = ".,;:!?)]}」』）、。"

internal fun parseSharedContent(intent: Intent): SharedContent? {
    if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return null
    val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
    val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf { it.isNotEmpty() }

    // テキストの共有(text/plain で本文がある)以外で、ファイルが付いていればファイルとして扱う
    val isPlainTextShare = intent.type?.startsWith("text/plain") == true && text.isNotEmpty()
    val streams = if (isPlainTextShare) emptyList() else sharedStreams(intent)
    if (streams.isNotEmpty()) {
        val files = streams.map { SelectedFile(uri = it.toString(), mimeType = intent.type, displayName = null, sizeBytes = null) }
        return SharedContent(url = null, text = text, tentativeTitle = subject.orEmpty(), files = files)
    }

    if (intent.action != Intent.ACTION_SEND || text.isEmpty()) return null

    val rawUrl = URL_REGEX.find(text)?.value
    val url = rawUrl?.trimEnd { it in TRAILING_PUNCTUATION }?.takeIf { it.length > "https://".length }

    if (url != null) {
        // 「タイトル + URL」形式で共有するアプリ向けに、URL以外の部分を仮タイトルの候補にする
        val remaining = text.replace(rawUrl, "").trim().lines().firstOrNull { it.isNotBlank() }?.trim()
        return SharedContent(url = url, text = text, tentativeTitle = subject ?: remaining ?: url)
    }
    val firstLine = text.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return SharedContent(url = null, text = text, tentativeTitle = subject ?: firstLine.take(80))
}

/**
 * 共有されたファイルの URI。content:// だけを受け付ける(file:// を受け付けると、他のアプリに
 * このアプリの内部ファイルを読み込ませられるため)。重複は除き、多すぎるときは先頭から [MAX_SHARED_FILES] 件まで。
 */
private fun sharedStreams(intent: Intent): List<Uri> {
    val fromExtra = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    } else {
        listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
    }
    // EXTRA_STREAM を付けず ClipData だけで渡すアプリもある
    val fromClip = intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
    return (fromExtra.ifEmpty { fromClip })
        .filter { it.scheme == ContentResolver.SCHEME_CONTENT }
        .distinct()
        .take(MAX_SHARED_FILES)
}

/** 1回の共有で保存するファイルの最大数 */
private const val MAX_SHARED_FILES = 50

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickSaveSheet(
    shared: SharedContent,
    status: ShareSaveStatus,
    onSave: (title: String, memo: String, tags: String) -> Unit,
    onDismiss: () -> Unit
) {
    // 仮タイトルが URL そのものなら空欄にして、ページのタイトルを自動で使う
    var title by rememberSaveable { mutableStateOf(shared.tentativeTitle.takeIf { it != shared.url }.orEmpty()) }
    // ファイルに添えられた文章(写真の説明など)はメモに入れておく
    var memo by rememberSaveable { mutableStateOf(if (shared.files.isNotEmpty()) shared.text else "") }
    var tags by rememberSaveable { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        ShareSheetContent(
            shared = shared,
            status = status,
            title = title,
            memo = memo,
            tags = tags,
            onTitleChange = { title = it },
            onMemoChange = { memo = it },
            onTagsChange = { tags = it },
            onSave = { onSave(title, memo, tags) },
            onCancel = onDismiss
        )
    }
}

/** 共有シートの中身(状態を受け取って描くだけ)。保存後は結果の表示に切り替える */
@Composable
fun ShareSheetContent(
    shared: SharedContent,
    status: ShareSaveStatus,
    title: String,
    memo: String,
    tags: String,
    onTitleChange: (String) -> Unit,
    onMemoChange: (String) -> Unit,
    onTagsChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedContent(targetState = status is ShareSaveStatus.Saved, label = "share_sheet", modifier = modifier) { saved ->
        if (saved) {
            SavedResult(
                status = status as? ShareSaveStatus.Saved ?: ShareSaveStatus.Saved(isDuplicate = false),
                isLink = shared.url != null,
                isFile = shared.files.isNotEmpty()
            )
        } else {
            ShareForm(
                shared = shared,
                status = status,
                title = title,
                memo = memo,
                tags = tags,
                onTitleChange = onTitleChange,
                onMemoChange = onMemoChange,
                onTagsChange = onTagsChange,
                onSave = onSave,
                onCancel = onCancel
            )
        }
    }
}

@Composable
private fun ShareForm(
    shared: SharedContent,
    status: ShareSaveStatus,
    title: String,
    memo: String,
    tags: String,
    onTitleChange: (String) -> Unit,
    onMemoChange: (String) -> Unit,
    onTagsChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val saving = status is ShareSaveStatus.Saving
    val hasFiles = shared.files.isNotEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    Icons.Filled.Bookmarks,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "EchoMarkに保存",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    if (hasFiles) "保存すると中身を読み取って、AI が要約します" else "保存すると AI が要約とタグ付けをします",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (hasFiles) SharedFilesPreview(shared.files) else SharedPreview(shared)

        // 複数のファイルは、それぞれのファイル名をタイトルにする
        if (shared.files.size <= 1) {
            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (shared.url != null || hasFiles) "タイトル(任意)" else "タイトル") },
                supportingText = when {
                    hasFiles -> {
                        { Text("空欄なら、ファイル名をタイトルにします") }
                    }
                    shared.url != null -> {
                        { Text("空欄なら、ページのタイトルを自動で取得します") }
                    }
                    else -> null
                },
                singleLine = true,
                enabled = !saving,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
        }
        OutlinedTextField(
            value = memo,
            onValueChange = onMemoChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("メモ(任意)") },
            enabled = !saving,
            minLines = 2
        )
        OutlinedTextField(
            value = tags,
            onValueChange = onTagsChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("タグ(任意)") },
            placeholder = { Text("例: Android, あとで読む") },
            supportingText = { Text("カンマや空白で区切ると複数付けられます") },
            singleLine = true,
            enabled = !saving,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
        )

        (status as? ShareSaveStatus.Failed)?.let { failed ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(failed.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel, enabled = !saving) { Text("キャンセル") }
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = !saving && if (hasFiles) {
                    shared.files.any { it.canSave }
                } else {
                    shared.url != null || title.isNotBlank() || shared.text.isNotBlank()
                },
                onClick = onSave
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    // saving のとき status は Saving(K2 のスマートキャスト)
                    Text(if (status.total > 1) "保存中… ${status.done}/${status.total}" else "保存中…")
                } else {
                    Text(if (shared.files.size > 1) "${shared.files.count { it.canSave }}件を保存" else "保存")
                }
            }
        }
    }
}

/** 共有された URL(ドメインと URL)またはテキストの冒頭を見せる */
@Composable
private fun SharedPreview(shared: SharedContent) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (shared.url != null) Icons.Outlined.Link else Icons.AutoMirrored.Outlined.Notes,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (shared.url != null) {
                    Text(
                        extractDomain(shared.url),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        shared.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        shared.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 共有されたファイルのプレビュー。1件の画像は大きく、それ以外の1件は種類のアイコンとファイル名、
 * 複数なら件数・合計サイズと、1件ずつのサムネイルと名前(多ければ先頭から数件)を出す。
 */
@Composable
private fun SharedFilesPreview(files: List<SelectedFile>) {
    val single = files.singleOrNull()
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (single != null) {
                if (single.type == BookmarkType.IMAGE) {
                    // 装飾扱い(内容はファイル名で伝わる)
                    AsyncImage(
                        model = single.uri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(MaterialTheme.shapes.small)
                    )
                }
                SelectedFileRow(single, showThumbnail = single.type != BookmarkType.IMAGE)
            } else {
                val total = files.sumOf { it.sizeBytes ?: 0L }
                Text(
                    "${files.size}件のファイル" + if (total > 0) " · 合計 ${formatFileSize(total)}" else "",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                files.take(MAX_LISTED_FILES).forEach { SelectedFileRow(it) }
                if (files.size > MAX_LISTED_FILES) {
                    Text(
                        "ほか${files.size - MAX_LISTED_FILES}件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 複数のファイルのとき、名前を並べる最大数 */
private const val MAX_LISTED_FILES = 4

/** 保存後に短く見せる結果 */
@Composable
private fun SavedResult(status: ShareSaveStatus.Saved, isLink: Boolean, isFile: Boolean) {
    val isDuplicate = status.isDuplicate
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp)
            .padding(24.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                when {
                    isDuplicate -> "既に保存済みです"
                    status.savedCount > 1 -> "${status.savedCount}件保存しました"
                    else -> "保存しました"
                },
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    isDuplicate -> "同じ URL のブックマークがあります"
                    status.failedCount > 0 -> "${status.failedCount}件は保存できませんでした(大きすぎる・読み込めないなど)"
                    isFile -> "中身を読み取って、AI が要約します"
                    isLink -> "ページを読み込んで、AI が要約します"
                    else -> "AI が要約とタグ付けをします"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
