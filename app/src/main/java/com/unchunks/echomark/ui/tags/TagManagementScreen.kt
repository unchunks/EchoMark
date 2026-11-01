package com.unchunks.echomark.ui.tags

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.automirrored.outlined.MergeType
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.components.composeui.ReadableLazyColumn
import com.unchunks.echomark.domain.model.TagNames
import com.unchunks.echomark.domain.model.TagNode
import com.unchunks.echomark.domain.model.TagTree
import com.unchunks.echomark.domain.model.TagWithCount
import com.unchunks.echomark.ui.components.AiTagIcon
import com.unchunks.echomark.ui.components.EmptyState
import com.unchunks.echomark.ui.components.ErrorState
import com.unchunks.echomark.ui.components.LoadingState
import kotlinx.coroutines.launch

/**
 * タグの管理(件数つき一覧・名前変更・統合・表記ゆれの整理・削除)。ViewModel をつなぐだけの薄いラッパー。
 * 自分のタグと AI が作ったタグを見分けて表示し、どちらかに絞り込める。
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
                        is TagManagementMessage.Unified -> "${message.count + 1}個のタグを「${message.intoName}」にまとめました"
                        is TagManagementMessage.ParentChanged ->
                            if (message.parentName != null) {
                                "「${message.name}」を「${message.parentName}」の下に入れました"
                            } else {
                                "「${message.name}」を最上位に戻しました"
                            }
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
        onDelete = viewModel::delete,
        onMerge = viewModel::merge,
        onUnify = viewModel::unify,
        onSetParent = viewModel::setParent
    )
}

/** タグ管理画面の本体(状態を受け取って描くだけ)。名前変更・統合・削除の確認ダイアログもここで出す */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagManagementContent(
    uiState: TagManagementUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onOpenTag: (Long) -> Unit,
    onRename: (TagWithCount, String) -> Unit,
    onDelete: (TagWithCount) -> Unit,
    modifier: Modifier = Modifier,
    onMerge: (from: TagWithCount, into: TagWithCount) -> Unit = { _, _ -> },
    onUnify: (group: List<TagWithCount>, into: TagWithCount) -> Unit = { _, _ -> },
    onSetParent: (tag: TagWithCount, parent: TagWithCount?) -> Unit = { _, _ -> }
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var renameTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var mergeTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var parentTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    // 表記ゆれの候補は、先頭のタグの ID で見分ける(まとめたあとは先頭が変わるのでダイアログは閉じる)
    var unifyGroupId by rememberSaveable { mutableStateOf<Long?>(null) }
    var originFilter by rememberSaveable { mutableStateOf(TagOriginFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(TagSort.NAME) }
    val visibleNodes = sort.arrange(uiState.tags, originFilter)

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
                else -> ReadableLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    if (uiState.variantGroups.isNotEmpty()) {
                        item(key = "variants-header") {
                            Text(
                                text = "表記ゆれの候補(${uiState.variantGroups.size}組)",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
                            )
                        }
                        items(uiState.variantGroups, key = { group -> "variants-${group.first().id}" }) { group ->
                            VariantGroupCard(
                                group = group,
                                onUnify = { unifyGroupId = group.first().id },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                    item(key = "filter") {
                        TagListOptionsRow(
                            origin = originFilter,
                            onOrigin = { originFilter = it },
                            sort = sort,
                            onSort = { sort = it }
                        )
                    }
                    item(key = "hint") {
                        Text(
                            text = "${visibleNodes.size}個のタグ · タップで絞り込み(下の階層も含む)",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(visibleNodes, key = { it.tag.id }) { (tag, depth) ->
                        TagRow(
                            tag = tag,
                            depth = depth,
                            parentName = if (sort == TagSort.TREE) null else uiState.tags.firstOrNull { it.id == tag.parentId }?.name,
                            onClick = { onOpenTag(tag.id) },
                            onRename = { renameTargetId = tag.id },
                            onParent = { parentTargetId = tag.id },
                            onMerge = { mergeTargetId = tag.id },
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

    uiState.tags.firstOrNull { it.id == parentTargetId }?.let { target ->
        val blocked = TagTree.descendantIds(uiState.tags, target.id) + target.id
        ParentPickerDialog(
            tag = target,
            candidates = uiState.tags.filter { it.id !in blocked },
            onDismiss = { parentTargetId = null },
            onConfirm = { parent ->
                parentTargetId = null
                onSetParent(target, parent)
            }
        )
    }

    uiState.tags.firstOrNull { it.id == mergeTargetId }?.let { target ->
        MergeIntoDialog(
            tag = target,
            candidates = uiState.tags.filter { it.id != target.id },
            onDismiss = { mergeTargetId = null },
            onConfirm = { into ->
                mergeTargetId = null
                onMerge(target, into)
            }
        )
    }

    uiState.variantGroups.firstOrNull { it.first().id == unifyGroupId }?.let { group ->
        UnifyVariantsDialog(
            group = group,
            onDismiss = { unifyGroupId = null },
            onConfirm = { into ->
                unifyGroupId = null
                onUnify(group, into)
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

/** 階層順で字下げする深さの上限(これより深いタグも同じ字下げにする) */
private const val MAX_INDENT_DEPTH = 4

/** タグ一覧の絞り込み(誰のタグか)。 */
internal enum class TagOriginFilter(val label: String) {
    ALL("すべて"),
    USER("自分"),
    AI("AI");

    fun matches(tag: TagWithCount): Boolean = when (this) {
        ALL -> true
        USER -> tag.isUserTag
        AI -> !tag.isUserTag
    }
}

/** タグ一覧の並び順。 */
internal enum class TagSort(val label: String) {
    NAME("名前順"),
    COUNT("件数順"),
    TREE("階層順");

    /** [origin] で絞った一覧をこの順に並べる(階層順は親の直後にその子を深さつきで並べる。絞り込みで親が外れても深さは保つ) */
    fun arrange(tags: List<TagWithCount>, origin: TagOriginFilter): List<TagNode> = when (this) {
        NAME -> tags.filter { origin.matches(it) }.map { TagNode(it, 0) }
        COUNT -> tags.filter { origin.matches(it) }
            .sortedWith(compareByDescending<TagWithCount> { it.bookmarkCount }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .map { TagNode(it, 0) }
        TREE -> TagTree.order(tags).filter { origin.matches(it.tag) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagListOptionsRow(
    origin: TagOriginFilter,
    onOrigin: (TagOriginFilter) -> Unit,
    sort: TagSort,
    onSort: (TagSort) -> Unit
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TagOriginFilter.entries.forEach { filter ->
                FilterChip(
                    selected = filter == origin,
                    onClick = { onOrigin(filter) },
                    label = { Text(filter.label) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TagSort.entries.forEach { option ->
                FilterChip(
                    selected = option == sort,
                    onClick = { onSort(option) },
                    label = { Text(option.label) }
                )
            }
        }
    }
}

/** 表記ゆれの候補1組。名前と件数を並べ、「まとめる」で残す名前を選ぶ。 */
@Composable
private fun VariantGroupCard(group: List<TagWithCount>, onUnify: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = group.joinToString(" / ") { "${it.name}(${it.bookmarkCount})" },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onUnify) { Text("まとめる") }
        }
    }
}

@Composable
private fun TagRow(
    tag: TagWithCount,
    depth: Int,
    parentName: String?,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onParent: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val count = if (tag.bookmarkCount > 0) "${tag.bookmarkCount}件" else "ブックマークなし"
            // AI のタグは、どのブックマークにも付かなくなると自動で消える(名前を変更すると自分のタグになる)
            val origin = if (tag.isUserTag) count else "${count} · AIが作成"
            Text(if (parentName != null) "${origin} · 親: ${parentName}" else origin)
        },
        leadingContent = {
            if (tag.isUserTag) {
                Icon(Icons.Outlined.Tag, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            } else {
                AiTagIcon(size = 24.dp, describe = true)
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "「${tag.name}」のメニュー")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("名前を変更") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("親のタグを設定") },
                        leadingIcon = { Icon(Icons.Outlined.AccountTree, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onParent()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("他のタグに統合") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.MergeType, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onMerge()
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
            .padding(start = (depth.coerceAtMost(MAX_INDENT_DEPTH) * 24).dp)
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
    val cleaned = TagNames.clean(name)
    val others = allTags.filter { it.id != tag.id }
    val mergeTarget = TagNames.findSame(cleaned, others.map { it.name })?.let { same -> others.first { it.name == same } }
    val canConfirm = cleaned.isNotEmpty() && cleaned != tag.name

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
                            cleaned.isEmpty() -> "タグ名を入力してください"
                            else -> "既存のタグと同じ名前(大文字小文字・全角半角の違いは無視)にすると、1つに統合されます"
                        }
                    )
                },
                isError = cleaned.isEmpty()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(cleaned) }, enabled = canConfirm) {
                Text(if (mergeTarget != null) "統合" else "変更")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

/** 統合先を選ぶダイアログ。タグが多いときは名前で絞り込める */
@Composable
private fun MergeIntoDialog(
    tag: TagWithCount,
    candidates: List<TagWithCount>,
    onDismiss: () -> Unit,
    onConfirm: (TagWithCount) -> Unit
) {
    var query by rememberSaveable(tag.id) { mutableStateOf("") }
    var selectedId by rememberSaveable(tag.id) { mutableStateOf<Long?>(null) }
    val queryKey = TagNames.key(query)
    val shown = candidates.filter { queryKey.isEmpty() || TagNames.key(it.name).contains(queryKey) }
    val selected = candidates.firstOrNull { it.id == selectedId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「${tag.name}」の統合先") },
        text = {
            Column {
                Text(
                    "このタグが付いたブックマークは、選んだタグに付け替わります。「${tag.name}」は削除されます。",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("タグを絞り込み") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
                if (shown.isEmpty()) {
                    Text(
                        "該当するタグがありません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                } else {
                    LazyColumn(Modifier.selectableGroup()) {
                        items(shown, key = { it.id }) { candidate ->
                            TagChoiceRow(
                                label = "${candidate.name}(${candidate.bookmarkCount}件)",
                                selected = candidate.id == selectedId,
                                onSelect = { selectedId = candidate.id }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { selected?.let(onConfirm) }, enabled = selected != null) { Text("統合") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

/** 親のタグを選ぶダイアログ(「なし」で最上位に戻す)。自分と自分の子孫は [candidates] に含めない */
@Composable
private fun ParentPickerDialog(
    tag: TagWithCount,
    candidates: List<TagWithCount>,
    onDismiss: () -> Unit,
    onConfirm: (TagWithCount?) -> Unit
) {
    var query by rememberSaveable(tag.id) { mutableStateOf("") }
    // 選び直すまでは今の親(最上位なら「なし」)
    var selectedId by rememberSaveable(tag.id) { mutableStateOf(tag.parentId) }
    val queryKey = TagNames.key(query)
    val shown = candidates.filter { queryKey.isEmpty() || TagNames.key(it.name).contains(queryKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「${tag.name}」の親のタグ") },
        text = {
            Column {
                Text(
                    "親のタグで絞り込むと、このタグのブックマークも含まれます。",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("タグを絞り込み") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
                LazyColumn(Modifier.selectableGroup()) {
                    item(key = "none") {
                        TagChoiceRow(label = "なし(最上位)", selected = selectedId == null, onSelect = { selectedId = null })
                    }
                    items(shown, key = { it.id }) { candidate ->
                        TagChoiceRow(
                            label = candidate.name,
                            selected = candidate.id == selectedId,
                            onSelect = { selectedId = candidate.id }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(candidates.firstOrNull { it.id == selectedId }) },
                enabled = selectedId != tag.parentId
            ) { Text("設定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

/** 表記ゆれの候補をまとめるダイアログ。残す名前を選ぶ(最初は先頭の候補) */
@Composable
private fun UnifyVariantsDialog(
    group: List<TagWithCount>,
    onDismiss: () -> Unit,
    onConfirm: (TagWithCount) -> Unit
) {
    var selectedId by rememberSaveable(group.first().id) { mutableStateOf(group.first().id) }
    val selected = group.firstOrNull { it.id == selectedId } ?: group.first()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("1つにまとめる") },
        text = {
            Column {
                Text(
                    "残す名前を選んでください。ほかのタグが付いたブックマークには、選んだタグが付きます。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Column(
                    Modifier
                        .selectableGroup()
                        .padding(top = 8.dp)
                ) {
                    group.forEach { tag ->
                        TagChoiceRow(
                            label = "${tag.name}(${tag.bookmarkCount}件)",
                            selected = tag.id == selected.id,
                            onSelect = { selectedId = tag.id }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text("まとめる") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

@Composable
private fun TagChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
