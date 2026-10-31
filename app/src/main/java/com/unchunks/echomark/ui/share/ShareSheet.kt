package com.unchunks.echomark.ui.share

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.model.TagNames
import com.unchunks.echomark.ui.common.SelectedFile
import com.unchunks.echomark.ui.common.extractDomain
import com.unchunks.echomark.ui.common.formatFileSize
import com.unchunks.echomark.ui.components.SelectedFileRow

/** 共有から開くクイック保存のボトムシート。入力中のタイトル・メモ・タグは回転などで作り直しても残す */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickSaveSheet(
    shared: SharedContent,
    status: ShareSaveStatus,
    onSave: (title: String, memo: String, tags: String) -> Unit,
    onDismiss: () -> Unit,
    suggestedTags: List<String> = emptyList(),
    onInputChange: (title: String, memo: String) -> Unit = { _, _ -> }
) {
    // 仮タイトルが URL そのものなら空欄にして、ページのタイトルを自動で使う
    var title by rememberSaveable { mutableStateOf(shared.tentativeTitle.takeIf { it != shared.url }.orEmpty()) }
    // ファイルに添えられた文章(写真の説明など)はメモに入れておく
    var memo by rememberSaveable { mutableStateOf(if (shared.files.isNotEmpty()) shared.text else "") }
    // 引用は「引用」のタグを最初から入れておく(外してもよい)
    var tags by rememberSaveable { mutableStateOf(if (shared.isQuote) QUOTE_TAG else "") }
    // タイトルとメモが変わるたびに、保存する内容に合うタグの提案を出し直す(開いた直後も1回)
    LaunchedEffect(title, memo) { onInputChange(title, memo) }

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
            suggestedTags = suggestedTags,
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
    modifier: Modifier = Modifier,
    suggestedTags: List<String> = emptyList()
) {
    AnimatedContent(targetState = status is ShareSaveStatus.Saved, label = "share_sheet", modifier = modifier) { saved ->
        if (saved) {
            SavedResult(
                status = status as? ShareSaveStatus.Saved ?: ShareSaveStatus.Saved(isDuplicate = false),
                isLink = shared.url != null || shared.entries.any { it.url != null },
                isFile = shared.files.isNotEmpty()
            )
        } else {
            ShareForm(
                shared = shared,
                status = status,
                title = title,
                memo = memo,
                tags = tags,
                suggestedTags = suggestedTags,
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
    suggestedTags: List<String>,
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
                    when {
                        hasFiles -> "保存すると中身を読み取って、AI が要約します"
                        shared.isQuote -> "選んだ文章を引用として保存します。AI が要約とタグ付けをします"
                        else -> "保存すると AI が要約とタグ付けをします"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        when {
            hasFiles -> SharedFilesPreview(shared.files)
            shared.hasMultipleEntries -> SharedEntriesPreview(shared.entries)
            else -> SharedPreview(shared)
        }

        // 複数のファイルは、それぞれのファイル名をタイトルにする。複数のテキスト・URL は、それぞれの内容からタイトルを決める
        if (shared.files.size <= 1 && !shared.hasMultipleEntries) {
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
        TagSuggestionRow(
            suggestions = suggestedTags,
            typed = ShareViewModel.parseTags(tags),
            enabled = !saving,
            onPick = { name -> onTagsChange(appendTag(tags, name)) }
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
                } else if (shared.hasMultipleEntries) {
                    true
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
                    Text(
                        when {
                            shared.files.size > 1 -> "${shared.files.count { it.canSave }}件を保存"
                            shared.hasMultipleEntries -> "${shared.entries.size}件を保存"
                            else -> "保存"
                        }
                    )
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

/** まとめて共有された URL・テキストのプレビュー。件数と、1件ずつのドメインまたは冒頭(多ければ先頭から数件)を出す */
@Composable
private fun SharedEntriesPreview(entries: List<SharedEntry>) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${entries.size}件を、それぞれ別のブックマークとして保存します",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            entries.take(MAX_LISTED_FILES).forEach { entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (entry.url != null) Icons.Outlined.Link else Icons.AutoMirrored.Outlined.Notes,
                        contentDescription = if (entry.url != null) "リンク" else "メモ",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        entry.url ?: entry.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (entries.size > MAX_LISTED_FILES) {
                Text(
                    "ほか${entries.size - MAX_LISTED_FILES}件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                    status.failedCount > 0 && isFile -> "${status.failedCount}件は保存できませんでした(大きすぎる・読み込めないなど)"
                    status.failedCount > 0 -> "${status.failedCount}件は保存できませんでした"
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

/** 保存する内容に合うタグの提案。タップで入力欄に足す(入力済みのタグは出さない)。出すものが無ければ何も描かない */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagSuggestionRow(suggestions: List<String>, typed: List<String>, enabled: Boolean, onPick: (String) -> Unit) {
    val typedKeys = typed.mapTo(HashSet()) { TagNames.key(it) }
    val shown = suggestions.filter { TagNames.key(it) !in typedKeys }
    if (shown.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "おすすめのタグ(タップで追加)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEach { name ->
                SuggestionChip(
                    onClick = { onPick(name) },
                    enabled = enabled,
                    label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.semantics { contentDescription = "タグ「$name」を追加" }
                )
            }
        }
    }
}

/** 入力欄の文字列にタグを1つ足す(区切りのカンマ・空白は入力のままにして、最後に「, 」でつなぐ)。 */
internal fun appendTag(input: String, name: String): String {
    val trimmed = input.trimEnd()
    return when {
        trimmed.isEmpty() -> name
        trimmed.endsWith(",") || trimmed.endsWith("、") -> "$trimmed $name"
        else -> "$trimmed, $name"
    }
}
