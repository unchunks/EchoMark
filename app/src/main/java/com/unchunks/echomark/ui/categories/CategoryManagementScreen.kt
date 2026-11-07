package com.unchunks.echomark.ui.categories

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
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.components.composeui.ReadableLazyColumn
import com.unchunks.echomark.domain.model.CategoryEntry
import com.unchunks.echomark.domain.model.CategoryKind
import com.unchunks.echomark.domain.model.CategoryNames
import com.unchunks.echomark.ui.components.ErrorState
import com.unchunks.echomark.ui.components.LoadingState
import kotlinx.coroutines.launch

/**
 * カテゴリの管理(自分のカテゴリを作る・直す・消す、標準のカテゴリと AI が付けた分類の説明)。ViewModel をつなぐだけの薄いラッパー。
 */
@Composable
fun CategoryManagementScreen(
    onBack: () -> Unit,
    viewModel: CategoryManagementViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            launch {
                snackbarHostState.showSnackbar(
                    when (message) {
                        is CategoryManagementMessage.Added -> "カテゴリ「${message.name}」を作りました"
                        is CategoryManagementMessage.Updated -> "カテゴリ「${message.name}」を保存しました"
                        is CategoryManagementMessage.Deleted -> "カテゴリ「${message.name}」を削除しました"
                        is CategoryManagementMessage.Failed -> message.message
                    }
                )
            }
        }
    }

    CategoryManagementContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onAdd = viewModel::add,
        onUpdate = viewModel::update,
        onDelete = viewModel::delete
    )
}

/** カテゴリ画面の本体(状態を受け取って描くだけ)。作る・直す・消すの確認ダイアログもここで出す */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManagementContent(
    uiState: CategoryManagementUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onAdd: (name: String, description: String) -> Unit,
    onUpdate: (entry: CategoryEntry, name: String, description: String) -> Unit,
    onDelete: (entry: CategoryEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    // 作る(newName は「自分のカテゴリにする」で名前を引き継ぐとき)・直す・消す。どれも1つだけ開く
    var addOpen by rememberSaveable { mutableStateOf(false) }
    var addName by rememberSaveable { mutableStateOf("") }
    var editName by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteName by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("カテゴリ", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { addName = ""; addOpen = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "カテゴリを作る")
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
                else -> {
                    val userEntries = uiState.entries.filter { it.kind == CategoryKind.USER }
                    val builtInEntries = uiState.entries.filter { it.kind == CategoryKind.BUILT_IN }
                    val otherEntries = uiState.entries.filter { it.kind == CategoryKind.AI_OTHER }
                    ReadableLazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        item(key = "about") { AboutCategoryCard() }
                        item(key = "user-header") {
                            SectionTitle("自分のカテゴリ(${userEntries.size})")
                        }
                        if (userEntries.isEmpty()) {
                            item(key = "user-empty") {
                                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "まだありません。作ると、AI が説明を読んで、内容に合うブックマークをそのカテゴリに入れます。",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    TextButton(onClick = { addName = ""; addOpen = true }) { Text("カテゴリを作る") }
                                }
                            }
                        }
                        items(userEntries, key = { "user-${it.id}" }) { entry ->
                            CategoryRow(
                                entry = entry,
                                onEdit = { editName = entry.name },
                                onDelete = { deleteName = entry.name }
                            )
                        }
                        item(key = "builtin-header") { SectionTitle("標準のカテゴリ") }
                        items(builtInEntries, key = { "builtin-${it.name}" }) { entry -> CategoryRow(entry = entry) }
                        if (otherEntries.isNotEmpty()) {
                            item(key = "other-header") { SectionTitle("AI が付けたその他の分類") }
                            items(otherEntries, key = { "other-${it.name}" }) { entry ->
                                CategoryRow(
                                    entry = entry,
                                    onMakeMine = { addName = entry.name; addOpen = true }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (addOpen) {
        CategoryEditDialog(
            title = "カテゴリを作る",
            initial = null,
            initialName = addName,
            others = uiState.entries,
            onDismiss = { addOpen = false },
            onConfirm = { name, description ->
                addOpen = false
                onAdd(name, description)
            }
        )
    }

    uiState.entries.firstOrNull { it.kind == CategoryKind.USER && it.name == editName }?.let { target ->
        CategoryEditDialog(
            title = "カテゴリを編集",
            initial = target,
            initialName = target.name,
            others = uiState.entries.filter { it.id != target.id },
            onDismiss = { editName = null },
            onConfirm = { name, description ->
                editName = null
                onUpdate(target, name, description)
            }
        )
    }

    uiState.entries.firstOrNull { it.kind == CategoryKind.USER && it.name == deleteName }?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteName = null },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text("カテゴリ「${target.name}」を削除しますか?") },
            text = {
                Text(
                    if (target.bookmarkCount > 0) {
                        "${target.bookmarkCount}件のブックマークは未分類に戻り、AI が次の処理でカテゴリを決め直します。ブックマーク自体は削除されません。"
                    } else {
                        "このカテゴリのブックマークはありません。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteName = null
                        onDelete(target)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { deleteName = null }) { Text("キャンセル") } }
        )
    }
}

/** 「カテゴリとは」の説明(カテゴリと、AI の分類の意味・仕組み)。 */
@Composable
private fun AboutCategoryCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Category, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    "カテゴリとは",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() }
                )
            }
            CategoryNames.ABOUT_CATEGORY.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            .semantics { heading() }
    )
}

/** カテゴリ1行。名前・件数・説明。自分のカテゴリは編集・削除、AI が付けた分類は「自分のカテゴリにする」のメニュー。 */
@Composable
private fun CategoryRow(
    entry: CategoryEntry,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onMakeMine: (() -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val count = if (entry.bookmarkCount > 0) "${entry.bookmarkCount}件" else "ブックマークなし"
            val description = entry.description.ifEmpty { "説明はありません" }
            Text("${count} · ${description}", maxLines = 3, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = if (onEdit != null || onMakeMine != null) {
            {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "「${entry.name}」のメニュー")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (onEdit != null) {
                            DropdownMenuItem(
                                text = { Text("編集") },
                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onEdit()
                                }
                            )
                        }
                        if (onDelete != null) {
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
                        if (onMakeMine != null) {
                            DropdownMenuItem(
                                text = { Text("自分のカテゴリにする") },
                                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onMakeMine()
                                }
                            )
                        }
                    }
                }
            }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth()
    )
}

/** カテゴリを作る・編集するダイアログ。名前と説明の検査はここでも行い、保存できないときは理由を出す。 */
@Composable
private fun CategoryEditDialog(
    title: String,
    initial: CategoryEntry?,
    initialName: String,
    others: List<CategoryEntry>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String) -> Unit
) {
    var name by rememberSaveable(initial?.id, initialName) { mutableStateOf(initialName) }
    var description by rememberSaveable(initial?.id) { mutableStateOf(initial?.description.orEmpty()) }
    val cleanedName = CategoryNames.clean(name)
    val key = CategoryNames.key(cleanedName)
    // 自分のカテゴリ・標準のカテゴリと同じ名前は作れない。AI が付けた分類と同じ名前は「自分のカテゴリにする」ことになる
    val duplicate = others.firstOrNull { it.kind != CategoryKind.AI_OTHER && CategoryNames.key(it.name) == key }
    val nameError = when {
        cleanedName.isEmpty() -> "カテゴリ名を入力してください"
        cleanedName.length > CategoryNames.MAX_NAME_CHARS -> "カテゴリ名は${CategoryNames.MAX_NAME_CHARS}文字までです"
        duplicate != null -> "「${duplicate.name}」と同じ名前のカテゴリがあります"
        else -> null
    }
    val descriptionTooLong = description.trim().length > CategoryNames.MAX_DESCRIPTION_CHARS

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("カテゴリ名") },
                    supportingText = { Text(nameError ?: "${cleanedName.length}/${CategoryNames.MAX_NAME_CHARS}文字") },
                    isError = nameError != null && cleanedName.isNotEmpty()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    minLines = 2,
                    maxLines = 4,
                    label = { Text("説明(任意)") },
                    placeholder = { Text("例: 家電や日用品など、買うかどうか迷っているもの") },
                    supportingText = {
                        Text(
                            if (descriptionTooLong) {
                                "説明は${CategoryNames.MAX_DESCRIPTION_CHARS}文字までです"
                            } else {
                                "AI がこの説明を読んで、内容に合うブックマークを入れます(${description.trim().length}/${CategoryNames.MAX_DESCRIPTION_CHARS}文字)"
                            }
                        )
                    },
                    isError = descriptionTooLong
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(cleanedName, description.trim()) },
                enabled = nameError == null && !descriptionTooLong
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}
