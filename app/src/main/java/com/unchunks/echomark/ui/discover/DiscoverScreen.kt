package com.unchunks.echomark.ui.discover

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkAdded
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.echomark.data.discover.DiscoveredResource
import com.unchunks.echomark.data.discover.DiscoveryStep
import com.unchunks.echomark.data.discover.ResourceSource
import com.unchunks.echomark.ui.chat.AiSetupBanner
import com.unchunks.components.composeui.MessageSnackbarEffect
import com.unchunks.components.composeui.ReadableLazyColumn
import com.unchunks.echomark.ui.components.SectionHeader
import kotlinx.coroutines.flow.map

/**
 * 資料を探す画面。テーマを入れると、Web の記事・動画・百科事典(と論文)を探して AI が役立つものを選び、
 * 選んだものをまとめてブックマークに追加できる。
 * @param onOpenPageOrFiles 一覧ページのリンク・端末内のファイルから探す画面を開く
 */
@Composable
fun DiscoverScreen(
    onBack: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenPageOrFiles: () -> Unit = {},
    viewModel: DiscoverViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val messages = remember(viewModel) { viewModel.uiState.map { it.message } }
    MessageSnackbarEffect(messages, snackbarHostState, onShown = viewModel::onMessageShown)

    DiscoverContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onOpenAiSettings = onOpenAiSettings,
        onTopicChange = viewModel::onTopicChange,
        onToggleSource = viewModel::toggleSource,
        onSearch = viewModel::search,
        onSearchExample = viewModel::searchTopic,
        onCancel = viewModel::cancel,
        onToggleSelection = viewModel::toggleSelection,
        onSave = viewModel::saveSelected,
        onOpenPageOrFiles = onOpenPageOrFiles
    )
}

/** 資料を探す画面の本体(状態を受け取って描くだけ)。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverContent(
    uiState: DiscoverUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onTopicChange: (String) -> Unit,
    onToggleSource: (ResourceSource) -> Unit,
    onSearch: () -> Unit,
    onSearchExample: (String) -> Unit,
    onCancel: () -> Unit,
    onToggleSelection: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPageOrFiles: () -> Unit = {}
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("資料を探す", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            val count = uiState.selectedUrls.size
            if (count > 0) SaveBar(count = count, isSaving = uiState.isSaving, onSave = onSave)
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        ReadableLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item(key = "setup") {
                AiSetupBanner(
                    state = uiState.aiSetup,
                    onOpenAiSettings = onOpenAiSettings,
                    compact = true,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            item(key = "form") {
                SearchForm(
                    uiState = uiState,
                    onTopicChange = onTopicChange,
                    onToggleSource = onToggleSource,
                    onSearch = onSearch,
                    onOpenPageOrFiles = onOpenPageOrFiles
                )
            }
            uiState.step?.let { step ->
                item(key = "progress") { ProgressRow(step = step, onCancel = onCancel) }
            }
            uiState.errorMessage?.let { message ->
                item(key = "error") { NoticeCard(message = message, isError = true) }
            }
            uiState.aiNotice?.let { notice ->
                item(key = "aiNotice") { NoticeCard(message = notice, isError = false) }
            }
            if (uiState.failedSources.isNotEmpty()) {
                item(key = "failed") {
                    Text(
                        text = uiState.failedSources.joinToString("・") { it.label } + "は検索できませんでした",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
            when {
                !uiState.hasSearched -> item(key = "intro") { Introduction(onSearchExample = onSearchExample) }
                uiState.step == null && uiState.resources.isEmpty() && uiState.errorMessage == null -> item(key = "empty") {
                    Text(
                        text = "資料が見つかりませんでした。テーマの言葉を変えるか、検索先を増やしてみてください。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            if (uiState.recommended.isNotEmpty()) {
                item(key = "recommendedHeader") { SectionHeader("AI のおすすめ") }
                items(uiState.recommended, key = { "r:" + it.candidate.url }) { resource ->
                    ResourceRow(
                        resource = resource,
                        isSelected = resource.candidate.url in uiState.selectedUrls,
                        onToggle = { onToggleSelection(resource.candidate.url) }
                    )
                }
            }
            if (uiState.others.isNotEmpty()) {
                item(key = "othersHeader") {
                    SectionHeader(if (uiState.recommended.isEmpty()) "見つかった資料" else "ほかの候補")
                }
                items(uiState.others, key = { "o:" + it.candidate.url }) { resource ->
                    ResourceRow(
                        resource = resource,
                        isSelected = resource.candidate.url in uiState.selectedUrls,
                        onToggle = { onToggleSelection(resource.candidate.url) }
                    )
                }
            }
        }
    }
}

/** テーマの入力欄と検索先のチップ。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchForm(
    uiState: DiscoverUiState,
    onTopicChange: (String) -> Unit,
    onToggleSource: (ResourceSource) -> Unit,
    onSearch: () -> Unit,
    onOpenPageOrFiles: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = uiState.topic,
            onValueChange = onTopicChange,
            label = { Text("調べたいテーマ") },
            placeholder = { Text("例: Jetpack Compose のパフォーマンス改善") },
            maxLines = 3,
            enabled = !uiState.isBusy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            trailingIcon = {
                IconButton(onClick = onSearch, enabled = uiState.canSearch) {
                    Icon(Icons.Outlined.Search, contentDescription = "探す")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ResourceSource.entries.forEach { source ->
                FilterChip(
                    selected = source in uiState.sources,
                    onClick = { onToggleSource(source) },
                    enabled = !uiState.isBusy,
                    label = { Text(source.label) },
                    leadingIcon = { Icon(source.icon(), contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }
        }
        if (ResourceSource.PAPER in uiState.sources) {
            Text(
                text = "論文は arXiv(英語)から、テーマの英語の語で探します",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 検索先を使わずに、自分で見つけた一覧ページや端末内のファイルの中から選ばせる探し方
        TextButton(onClick = onOpenPageOrFiles, enabled = !uiState.isBusy) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("一覧ページ・端末内のファイルから探す")
        }
    }
}

/** 探している途中の表示。 */
@Composable
private fun ProgressRow(step: DiscoveryStep, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = when (step) {
                DiscoveryStep.SEARCHING -> "資料を検索しています…"
                DiscoveryStep.SELECTING -> "AI が役立つものを選んでいます…(端末内 AI では数十秒かかることがあります)"
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onCancel) { Text("中止") }
    }
}

@Composable
private fun NoticeCard(message: String, isError: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
        )
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 探す前の案内と、テーマの例。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Introduction(onSearchExample: (String) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.TravelExplore, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("AI が資料を探して選びます", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "テーマを入れると、Web の記事・YouTube の動画・Wikipedia から資料を探し、AI が役立つものを選びます。" +
                "選ばれたものを確かめて、まとめてブックマークに追加できます。追加した資料は、いつもどおり AI が要約とタグ付けをします。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text("例", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EXAMPLE_TOPICS.forEach { topic ->
                SuggestionChip(onClick = { onSearchExample(topic) }, label = { Text(topic) })
            }
        }
    }
}

/** 見つかった資料の1行。タップで選択を切り替える。右のボタンでブラウザで開く。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResourceRow(resource: DiscoveredResource, isSelected: Boolean, onToggle: () -> Unit) {
    val candidate = resource.candidate
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !resource.isSaved, role = Role.Checkbox, onClick = onToggle)
            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (resource.isSaved) {
            Icon(
                Icons.Outlined.BookmarkAdded,
                contentDescription = "保存済み",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(12.dp).size(24.dp)
            )
        } else {
            Checkbox(checked = isSelected, onCheckedChange = { onToggle() })
        }
        Column(modifier = Modifier.weight(1f).padding(top = 10.dp)) {
            Text(
                text = candidate.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOfNotNull(
                    candidate.source.label,
                    candidate.siteName,
                    if (resource.isSaved) "保存済み" else null
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            resource.reason?.let { reason ->
                Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp).size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (candidate.snippet.isNotBlank()) {
                Text(
                    text = candidate.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        IconButton(onClick = { runCatching { uriHandler.openUri(candidate.url) } }) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "ブラウザで開く", modifier = Modifier.size(20.dp))
        }
    }
}

/** 選んだ資料を追加するボタンの帯。 */
@Composable
private fun SaveBar(count: Int, isSaving: Boolean, onSave: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Button(onClick = onSave, enabled = !isSaving, modifier = Modifier.fillMaxWidth()) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("選んだ${count}件をブックマークに追加")
            }
        }
    }
}

private fun ResourceSource.icon(): ImageVector = when (this) {
    ResourceSource.ARTICLE -> Icons.AutoMirrored.Outlined.Article
    ResourceSource.VIDEO -> Icons.Outlined.PlayCircle
    ResourceSource.ENCYCLOPEDIA -> Icons.AutoMirrored.Outlined.MenuBook
    ResourceSource.PAPER -> Icons.Outlined.School
}

/** 探す前に出すテーマの例。押すとそのテーマで探す */
private val EXAMPLE_TOPICS = listOf("Kotlin コルーチン 入門", "睡眠の質を上げる方法", "家庭菜園の始め方")
