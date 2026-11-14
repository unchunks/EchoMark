package com.unchunks.echomark.ui.selection

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.LabelOff
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkCollection
import com.unchunks.echomark.domain.bookmark.model.CollectionNames
import com.unchunks.echomark.domain.bookmark.model.CollectionResult
import com.unchunks.echomark.domain.export.BookmarkExporter
import com.unchunks.echomark.domain.export.ExportFormat
import com.unchunks.echomark.domain.model.CategoryEntry
import com.unchunks.echomark.ui.detail.ExportFormatDialog

/** 複数選択の操作。状態を持たない [BulkActionBar] に渡す。操作の対象は、呼び出し側が選んでいるものにする。 */
class BulkActionCallbacks(
    val onSetFavorite: (Boolean) -> Unit = {},
    val onSetRead: (Boolean) -> Unit = {},
    val onSetArchived: (Boolean) -> Unit = {},
    val onDelete: () -> Unit = {},
    val onAddTag: (String) -> Unit = {},
    val onRemoveTag: (String) -> Unit = {},
    val onSetCategory: (String) -> Unit = {},
    val onAddToCollection: (BookmarkCollection) -> Unit = {},
    /** 新しいコレクションを作って入れる。名前の誤りは、渡したコールバックで返す */
    val onCreateCollectionAndAdd: (String, (CollectionResult) -> Unit) -> Unit = { _, _ -> },
    /** コレクションの画面でだけ渡す(「コレクションから外す」を出す) */
    val onRemoveFromCollection: (() -> Unit)? = null,
    val onReprocess: () -> Unit = {},
    /** 形式と、選んだ保存先([android.net.Uri] の文字列) */
    val onExport: (ExportFormat, String) -> Unit = { _, _ -> },
    /** 1件だけ選んでいるときの「共有」 */
    val onShare: (Bookmark) -> Unit = {}
)

private const val DIALOG_ADD_TAG = "add_tag"
private const val DIALOG_REMOVE_TAG = "remove_tag"
private const val DIALOG_CATEGORY = "category"
private const val DIALOG_COLLECTION = "collection"
private const val DIALOG_REPROCESS = "reprocess"
private const val DIALOG_EXPORT = "export"

/**
 * 複数選択の間、画面の下に出す操作バー。よく使う4つ(アーカイブ・お気に入り・既読・削除)を並べ、残りは「その他」のメニューに入れる
 * (タグを付ける・外す、カテゴリを設定、コレクションに追加、再処理、書き出し、1件だけ選んでいるときの共有)。
 * アーカイブ・お気に入り・既読は、選んだものがすべてそうなら元に戻す操作に、そうでなければ(一部だけでも)する操作になる。
 * 選んだものが無いときは操作できない。
 *
 * @param tagSuggestions 「タグを付ける」で候補に出すタグ名
 * @param categories 「カテゴリを設定」の選択肢
 * @param collections 「コレクションに追加」の選択肢
 */
@Composable
fun BulkActionBar(
    selected: List<Bookmark>,
    tagSuggestions: List<String>,
    categories: List<CategoryEntry>,
    collections: List<BookmarkCollection>,
    callbacks: BulkActionCallbacks,
    modifier: Modifier = Modifier
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val enabled = selected.isNotEmpty()
    val allFavorite = enabled && selected.all { it.isFavorite }
    val allRead = enabled && selected.all { it.isRead }
    val allArchived = enabled && selected.all { it.isArchived }

    // 書き出し先を選ぶ画面は MIME タイプごとに作る(形式ごとに用意)
    val markdownLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.MARKDOWN.mimeType)) { uri ->
        if (uri != null) callbacks.onExport(ExportFormat.MARKDOWN, uri.toString())
    }
    val htmlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.BROWSER_HTML.mimeType)) { uri ->
        if (uri != null) callbacks.onExport(ExportFormat.BROWSER_HTML, uri.toString())
    }

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { callbacks.onSetArchived(!allArchived) }, enabled = enabled) {
                Icon(
                    if (allArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                    contentDescription = if (allArchived) "アーカイブから戻す" else "アーカイブ"
                )
            }
            IconButton(onClick = { callbacks.onSetFavorite(!allFavorite) }, enabled = enabled) {
                Icon(
                    if (allFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (allFavorite) "お気に入りから外す" else "お気に入りに追加"
                )
            }
            IconButton(onClick = { callbacks.onSetRead(!allRead) }, enabled = enabled) {
                Icon(
                    if (allRead) Icons.Outlined.MarkEmailUnread else Icons.Outlined.TaskAlt,
                    contentDescription = if (allRead) "未読に戻す" else "既読にする"
                )
            }
            IconButton(onClick = callbacks.onDelete, enabled = enabled) {
                Icon(Icons.Outlined.Delete, contentDescription = "削除", tint = MaterialTheme.colorScheme.error.takeIf { enabled } ?: MaterialTheme.colorScheme.onSurface)
            }
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "その他の操作")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (selected.size == 1) {
                        MenuItem("共有", Icons.Outlined.Share) {
                            menuOpen = false
                            callbacks.onShare(selected.single())
                        }
                    }
                    MenuItem("タグを付ける", Icons.Outlined.LocalOffer) {
                        menuOpen = false
                        dialog = DIALOG_ADD_TAG
                    }
                    MenuItem("タグを外す", Icons.Outlined.LabelOff) {
                        menuOpen = false
                        dialog = DIALOG_REMOVE_TAG
                    }
                    MenuItem("カテゴリを設定", Icons.Outlined.Category) {
                        menuOpen = false
                        dialog = DIALOG_CATEGORY
                    }
                    MenuItem("コレクションに追加", Icons.Outlined.CreateNewFolder) {
                        menuOpen = false
                        dialog = DIALOG_COLLECTION
                    }
                    callbacks.onRemoveFromCollection?.let { remove ->
                        MenuItem("コレクションから外す", Icons.Outlined.RemoveCircleOutline) {
                            menuOpen = false
                            remove()
                        }
                    }
                    HorizontalDivider()
                    MenuItem("AI で再処理", Icons.Outlined.Refresh) {
                        menuOpen = false
                        dialog = DIALOG_REPROCESS
                    }
                    MenuItem("ファイルに書き出す", Icons.Outlined.FileUpload) {
                        menuOpen = false
                        dialog = DIALOG_EXPORT
                    }
                }
            }
        }
    }

    val close = { dialog = null }
    when (dialog) {
        DIALOG_ADD_TAG -> AddTagDialog(
            suggestions = tagSuggestions,
            count = selected.size,
            onConfirm = { name ->
                close()
                callbacks.onAddTag(name)
            },
            onDismiss = close
        )
        DIALOG_REMOVE_TAG -> RemoveTagDialog(
            tags = selected.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key },
            onSelect = { name ->
                close()
                callbacks.onRemoveTag(name)
            },
            onDismiss = close
        )
        DIALOG_CATEGORY -> CategoryChoiceSheet(
            categories = categories,
            onSelect = { name ->
                close()
                callbacks.onSetCategory(name)
            },
            onDismiss = close
        )
        DIALOG_COLLECTION -> CollectionChoiceSheet(
            collections = collections,
            onSelect = { collection ->
                close()
                callbacks.onAddToCollection(collection)
            },
            onCreate = { name, onResult ->
                callbacks.onCreateCollectionAndAdd(name) { result ->
                    // 作れたら、入れて閉じる(名前の誤りなら開いたまま、その知らせを出す)
                    if (result is CollectionResult.Saved) close()
                    onResult(result)
                }
            },
            onDismiss = close
        )
        DIALOG_REPROCESS -> ReprocessDialog(
            count = selected.size,
            onConfirm = {
                close()
                callbacks.onReprocess()
            },
            onDismiss = close
        )
        DIALOG_EXPORT -> ExportFormatDialog(
            onSelect = { format ->
                close()
                val fileName = BookmarkExporter.fileName(BulkBookmarkActions.EXPORT_TITLE, format)
                when (format) {
                    ExportFormat.MARKDOWN -> markdownLauncher.launch(fileName)
                    ExportFormat.BROWSER_HTML -> htmlLauncher.launch(fileName)
                }
            },
            onDismiss = close
        )
    }
}

@Composable
private fun MenuItem(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick
    )
}

/** タグの名前を入れて、選んだすべてに付ける。候補は、すでにあるタグ(タップで入力に入る) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddTagDialog(suggestions: List<String>, count: Int, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${count}件にタグを付ける") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("タグの名前") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                val candidates = suggestions.filter { name.isBlank() || it.contains(name.trim(), ignoreCase = true) }.take(MAX_TAG_SUGGESTIONS)
                if (candidates.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        candidates.forEach { tag ->
                            FilterChip(selected = tag == name.trim(), onClick = { name = tag }, label = { Text(tag, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("付ける") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

/** 選んだものに付いているタグから、外すものを選ぶ(多く付いているものから) */
@Composable
private fun RemoveTagDialog(tags: List<String>, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("外すタグを選ぶ") },
        text = {
            if (tags.isEmpty()) {
                Text("選んだブックマークには、タグが付いていません。")
            } else {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    tags.forEach { tag ->
                        Text(
                            text = "#$tag",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(tag) }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

/** カテゴリを選ぶ。選んだすべてを、自分で選んだカテゴリにする(AI の再処理では置き換わらない) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryChoiceSheet(categories: List<CategoryEntry>, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text("カテゴリを設定", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(
                "選んだすべてのカテゴリを変えます。自分で選んだカテゴリは、AI の再処理でも変わりません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                categories.forEach { category ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(category.name) }
                            .padding(vertical = 10.dp)
                    ) {
                        Text(category.name, style = MaterialTheme.typography.bodyLarge)
                        if (category.description.isNotBlank()) {
                            Text(
                                category.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/** コレクションを選んで、選んだすべてを入れる。名前を入れて新しく作って入れることもできる */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionChoiceSheet(
    collections: List<BookmarkCollection>,
    onSelect: (BookmarkCollection) -> Unit,
    onCreate: (String, (CollectionResult) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("コレクションに追加", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            if (collections.isEmpty()) {
                Text(
                    "コレクションはまだありません。下で名前を付けて作ると、選んだものが入ります。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    collections.forEach { collection ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(collection) }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(collection.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${collection.bookmarkCount}件",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = newName,
                onValueChange = {
                    newName = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("新しいコレクションの名前") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { message -> { Text(message) } }
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        onCreate(newName) { result ->
                            when (result) {
                                is CollectionResult.Saved -> Unit
                                CollectionResult.InvalidName -> error = "名前は${CollectionNames.MAX_CHARS}文字までで入力してください"
                                CollectionResult.NameTaken -> error = "同じ名前のコレクションがあります"
                                CollectionResult.NotFound -> error = "保存できませんでした"
                            }
                        }
                    }
                ) { Text("作って追加") }
                TextButton(onClick = onDismiss) { Text("閉じる") }
            }
        }
    }
}

/** 再処理の確認。要約を作り直すので、クラウドの AI を使っているときは利用量がかかることを伝える */
@Composable
private fun ReprocessDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${count}件を AI で再処理しますか?") },
        text = {
            Text(
                "要約・タグ・カテゴリを作り直します(自分で直した要約と、自分で選んだカテゴリは変わりません)。" +
                    "1件ずつ順番に処理するので、終わるまで時間がかかります。クラウドの AI を使っている場合は、その分の利用量がかかります。"
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("再処理する") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

/** 「タグを付ける」で一度に出す候補の最大数(入力で絞り込める。スクロールさせずに済む数) */
private const val MAX_TAG_SUGGESTIONS = 12
