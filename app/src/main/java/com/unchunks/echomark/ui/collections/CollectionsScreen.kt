package com.unchunks.echomark.ui.collections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
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
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.components.composeui.ReadableLazyColumn
import com.unchunks.echomark.domain.bookmark.model.BookmarkCollection
import com.unchunks.echomark.domain.bookmark.model.CollectionNames
import com.unchunks.echomark.domain.bookmark.model.CollectionResult
import com.unchunks.echomark.ui.components.EmptyState
import com.unchunks.echomark.ui.components.ErrorState
import com.unchunks.echomark.ui.components.LoadingState
import kotlinx.coroutines.launch

/**
 * コレクションの一覧(#91)。ViewModel をつなぐだけの薄いラッパー。
 * @param onOpenCollection コレクションをタップしたとき(中身の画面を開く)
 */
@Composable
fun CollectionsScreen(
    onBack: () -> Unit,
    onOpenCollection: (Long) -> Unit,
    viewModel: CollectionsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            launch {
                when (message) {
                    is CollectionsMessage.Deleted -> {
                        val result = snackbarHostState.showSnackbar(
                            message = "コレクション「${message.deleted.name}」を削除しました",
                            actionLabel = "元に戻す",
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.restore(message.deleted)
                    }
                    CollectionsMessage.RestoreFailed ->
                        snackbarHostState.showSnackbar("元に戻せませんでした。同じ名前のコレクションがあるかもしれません")
                    is CollectionsMessage.Failed -> snackbarHostState.showSnackbar(message.message)
                }
            }
        }
    }

    CollectionsContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onOpenCollection = onOpenCollection,
        onCreate = viewModel::create,
        onUpdate = viewModel::update,
        onDelete = viewModel::delete
    )
}

/** コレクション一覧の本体(状態を受け取って描くだけ)。作る・名前を変えるダイアログと、削除の確認もここで出す。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsContent(
    uiState: CollectionsUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onOpenCollection: (Long) -> Unit,
    onCreate: (name: String, description: String, onResult: (CollectionResult) -> Unit) -> Unit,
    onUpdate: (id: Long, name: String, description: String, onResult: (CollectionResult) -> Unit) -> Unit,
    onDelete: (BookmarkCollection) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var createOpen by rememberSaveable { mutableStateOf(false) }
    var editId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("コレクション", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { createOpen = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "コレクションを作る")
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
                uiState.collections.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.CreateNewFolder,
                    title = "コレクションはまだありません",
                    description = "旅行の計画や調べもののように、自分で選んだブックマークを集めて名前を付けられます。" +
                        "あとから Markdown やブラウザのブックマークのファイルに書き出して、ほかへ持ち出すこともできます。",
                    actionLabel = "コレクションを作る",
                    onAction = { createOpen = true }
                )
                else -> ReadableLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(uiState.collections.size, key = { uiState.collections[it].id }) { index ->
                        val collection = uiState.collections[index]
                        CollectionRow(
                            collection = collection,
                            onClick = { onOpenCollection(collection.id) },
                            onEdit = { editId = collection.id },
                            onDelete = { deleteId = collection.id },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }

    if (createOpen) {
        CollectionNameDialog(
            title = "コレクションを作る",
            initialName = "",
            initialDescription = "",
            onSubmit = onCreate,
            onDismiss = { createOpen = false }
        )
    }
    uiState.collections.firstOrNull { it.id == editId }?.let { target ->
        CollectionNameDialog(
            title = "コレクションを編集",
            initialName = target.name,
            initialDescription = target.description.orEmpty(),
            onSubmit = { name, description, onResult -> onUpdate(target.id, name, description, onResult) },
            onDismiss = { editId = null }
        )
    }
    uiState.collections.firstOrNull { it.id == deleteId }?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text("コレクションを削除しますか?") },
            text = { Text("「${target.name}」を削除します。入れているブックマークは削除されません。削除したあとも、しばらくは元に戻せます。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteId = null
                        onDelete(target)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("キャンセル") } }
        )
    }
}

@Composable
private fun CollectionRow(
    collection: BookmarkCollection,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(collection.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                text = listOfNotNull("${collection.bookmarkCount}件", collection.description?.takeIf { it.isNotBlank() }).joinToString(" · "),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "「${collection.name}」のメニュー")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("名前・説明を編集") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("削除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
    )
}

/**
 * コレクションの名前と説明を入力するダイアログ。[onSubmit] の結果が保存できた([CollectionResult.Saved])ときだけ閉じ、
 * 名前が空・長すぎる・重複のときは、ダイアログの中に理由を出す。
 */
@Composable
fun CollectionNameDialog(
    title: String,
    initialName: String,
    initialDescription: String,
    onSubmit: (name: String, description: String, onResult: (CollectionResult) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var description by rememberSaveable { mutableStateOf(initialDescription) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = { Text("名前") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { Text(error ?: "${name.trim().length}/${CollectionNames.MAX_CHARS}文字") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(CollectionNames.MAX_DESCRIPTION_CHARS) },
                    label = { Text("説明(省略できます)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSubmit(name, description) { result ->
                        when (result) {
                            is CollectionResult.Saved -> onDismiss()
                            CollectionResult.InvalidName -> error = "名前は${CollectionNames.MAX_CHARS}文字までで入力してください"
                            CollectionResult.NameTaken -> error = "同じ名前のコレクションがあります"
                            CollectionResult.NotFound -> {
                                error = "コレクションが見つかりません。削除された可能性があります"
                            }
                        }
                    }
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}
