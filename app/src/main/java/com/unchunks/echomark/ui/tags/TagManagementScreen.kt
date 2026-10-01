package com.unchunks.echomark.ui.tags

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.echomark.domain.model.TagWithCount
import com.unchunks.echomark.ui.components.EmptyState
import com.unchunks.echomark.ui.components.ErrorState
import com.unchunks.echomark.ui.components.LoadingState
import kotlinx.coroutines.launch

/**
 * タグの管理(件数つき一覧・名前変更・統合・削除)。ViewModel をつなぐだけの薄いラッパー。
 * @param onOpenTag タグをタップしたとき。そのタグで絞り込んだ一覧へ戻る
 */
@Composable
fun TagManagementScreen(
    onBack: () -> Unit,
    onOpenTag: (Long) -> Unit,
    viewModel: TagManagementViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            launch {
                snackbarHostState.showSnackbar(
                    when (message) {
                        is TagManagementMessage.Renamed -> "「${message.newName}」に変更しました"
                        is TagManagementMessage.Merged -> "「${message.fromName}」を「${message.intoName}」に統合しました"
                        is TagManagementMessage.Deleted -> "タグ「${message.name}」を削除しました"
                        is TagManagementMessage.Failed -> message.message
                    }
                )
            }
        }
    }

    TagManagementContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onOpenTag = onOpenTag,
        onRename = viewModel::rename,
        onDelete = viewModel::delete
    )
}

/** タグ管理画面の本体(状態を受け取って描くだけ)。名前変更・削除の確認ダイアログもここで出す */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagManagementContent(
    uiState: TagManagementUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onOpenTag: (Long) -> Unit,
    onRename: (TagWithCount, String) -> Unit,
    onDelete: (TagWithCount) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var renameTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteTargetId by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("タグの管理", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
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
                uiState.tags.isEmpty() -> EmptyState(
                    icon = Icons.AutoMirrored.Outlined.Label,
                    title = "タグはまだありません",
                    description = "ブックマークを保存すると AI がタグを付けます。詳細画面から自分で付けることもできます。"
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    item(key = "hint") {
                        Text(
                            text = "${uiState.tags.size}個のタグ · タップで一覧を絞り込み",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(uiState.tags, key = { it.id }) { tag ->
                        TagRow(
                            tag = tag,
                            onClick = { onOpenTag(tag.id) },
                            onRename = { renameTargetId = tag.id },
                            onDelete = { deleteTargetId = tag.id },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }

    uiState.tags.firstOrNull { it.id == renameTargetId }?.let { target ->
        RenameTagDialog(
            tag = target,
            allTags = uiState.tags,
            onDismiss = { renameTargetId = null },
            onConfirm = { newName ->
                renameTargetId = null
                onRename(target, newName)
            }
        )
    }

    uiState.tags.firstOrNull { it.id == deleteTargetId }?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTargetId = null },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text("タグ「${target.name}」を削除しますか?") },
            text = {
                Text(
                    if (target.bookmarkCount > 0) {
                        "${target.bookmarkCount}件のブックマークからこのタグが外れます。ブックマーク自体は削除されません。"
                    } else {
                        "このタグの付いたブックマークはありません。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTargetId = null
                        onDelete(target)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { deleteTargetId = null }) { Text("キャンセル") } }
        )
    }
}

@Composable
private fun TagRow(
    tag: TagWithCount,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(if (tag.bookmarkCount > 0) "${tag.bookmarkCount}件" else "ブックマークなし")
        },
        leadingContent = {
            Icon(Icons.Outlined.Tag, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "「${tag.name}」のメニュー")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("名前を変更・統合") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("削除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "このタグで絞り込む", onClick = onClick)
    )
}

/** 名前変更ダイアログ。既存の別タグと同じ名前にすると、統合されることを先に伝える */
@Composable
private fun RenameTagDialog(
    tag: TagWithCount,
    allTags: List<TagWithCount>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by rememberSaveable(tag.id) { mutableStateOf(tag.name) }
    val trimmed = name.trim()
    val mergeTarget = allTags.firstOrNull { it.id != tag.id && it.name == trimmed }
    val canConfirm = trimmed.isNotEmpty() && trimmed != tag.name

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("タグ名を変更") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("タグ名") },
                supportingText = {
                    Text(
                        when {
                            mergeTarget != null ->
                                "「${mergeTarget.name}」(${mergeTarget.bookmarkCount}件)と統合されます"
                            trimmed.isEmpty() -> "タグ名を入力してください"
                            else -> "既存のタグと同じ名前にすると、1つに統合されます"
                        }
                    )
                },
                isError = trimmed.isEmpty()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(trimmed) }, enabled = canConfirm) {
                Text(if (mergeTarget != null) "統合" else "変更")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}
