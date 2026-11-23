package com.unchunks.echomark.ui.subscription

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.echomark.domain.subscription.MAX_NEW_ITEMS_PER_FEED
import com.unchunks.echomark.domain.subscription.MAX_NEW_ITEMS_PER_RUN
import com.unchunks.echomark.domain.subscription.QueryKind
import com.unchunks.echomark.domain.subscription.SearchGroup
import com.unchunks.echomark.domain.subscription.SearchSource
import com.unchunks.echomark.domain.subscription.Subscription
import com.unchunks.echomark.domain.subscription.SubscriptionType
import com.unchunks.echomark.ui.common.extractDomain
import com.unchunks.components.composeui.formatRelativeTime

/** 購読するフィードの管理シート。設定から開く。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsSheet(onDismiss: () -> Unit, viewModel: SubscriptionViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        SubscriptionsContent(
            state = state,
            onAdd = viewModel::add,
            onAddSearch = viewModel::addSearch,
            onCheckNow = viewModel::checkNow,
            onSetEnabled = viewModel::setEnabled,
            onRemove = viewModel::remove,
            onDismissMessage = viewModel::dismissMessage
        )
    }
}

/** 購読の管理の中身(状態を受け取って描くだけなので、スクリーンショットテストで単体描画できる) */
@Composable
fun SubscriptionsContent(
    state: SubscriptionsUiState,
    onAdd: (input: String, onAdded: () -> Unit) -> Unit,
    onCheckNow: () -> Unit,
    onSetEnabled: (id: Long, enabled: Boolean) -> Unit,
    onRemove: (id: Long) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
    onAddSearch: (source: SearchSource, kind: QueryKind, query: String, onAdded: () -> Unit) -> Unit = { _, _, _, _ -> },
    nowMillis: Long = System.currentTimeMillis(),
    initialMode: SubscribeMode = SubscribeMode.Site,
    initialSource: SearchSource = SearchSource.ARXIV
) {
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var input by rememberSaveable { mutableStateOf("") }
    var searchSource by rememberSaveable { mutableStateOf(initialSource) }
    var queryKind by rememberSaveable { mutableStateOf(initialSource.kinds.first()) }
    var query by rememberSaveable { mutableStateOf("") }
    var removeTarget by rememberSaveable { mutableStateOf<Long?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "購読",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = "ブログや YouTube のチャンネルの新着、キーワード・タグに当てはまる新しい論文・ニュース・技術記事、" +
                "GitHub のリリースを、ブックマークとして自動で保存します。" +
                "新着は毎晩(設定の夜間の時刻に)確認し、「充電中だけ」「Wi-Fi など従量課金でない接続のときだけ」の設定を守ります。" +
                "1回に保存するのは全体で最大 $MAX_NEW_ITEMS_PER_RUN 件(1つのフィードから $MAX_NEW_ITEMS_PER_FEED 件まで)で、" +
                "保存したものは1件ずつ順番にページを取得して要約します。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SubscribeMode.entries.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = mode == item,
                    onClick = { mode = item },
                    shape = SegmentedButtonDefaults.itemShape(index, SubscribeMode.entries.size),
                    enabled = !state.isBusy
                ) { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }

        when (mode) {
            SubscribeMode.Site -> OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("サイト・フィード・YouTube のチャンネルの URL") },
                placeholder = { Text("https://") },
                supportingText = { Text("サイトの URL を入れると、フィードを自動で探します") },
                singleLine = true,
                enabled = !state.isBusy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done)
            )
            SubscribeMode.Search -> SearchInput(
                source = searchSource,
                onSourceChange = {
                    searchSource = it
                    if (queryKind !in it.kinds) queryKind = it.kinds.first()
                },
                kind = queryKind,
                onKindChange = { queryKind = it },
                query = query,
                onQueryChange = { query = it },
                enabled = !state.isBusy
            )
        }
        val canAdd = when (mode) {
            SubscribeMode.Site -> input.isNotBlank()
            SubscribeMode.Search -> query.isNotBlank()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    when (mode) {
                        SubscribeMode.Site -> onAdd(input) { input = "" }
                        SubscribeMode.Search -> onAddSearch(searchSource, queryKind, query) { query = "" }
                    }
                },
                enabled = canAdd && !state.isBusy
            ) { Text("購読する") }
            OutlinedButton(onClick = onCheckNow, enabled = state.subscriptions.any { it.enabled } && !state.isBusy) {
                Text("いま確認する")
            }
            if (state.isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        if (state.isBusy) {
            Text(
                when {
                    state.operation != SubscriptionOperation.Adding -> "新着を確認しています…"
                    mode == SubscribeMode.Search -> "購読を追加しています…"
                    else -> "フィードを探しています…"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        state.message?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismissMessage) { Text("閉じる") }
            }
        }

        HorizontalDivider()
        if (state.subscriptions.isEmpty()) {
            Text(
                "まだ購読していません。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            state.subscriptions.forEach { subscription ->
                SubscriptionRow(
                    subscription = subscription,
                    nowMillis = nowMillis,
                    onSetEnabled = { onSetEnabled(subscription.id, it) },
                    onRemove = { removeTarget = subscription.id }
                )
            }
        }
    }

    removeTarget?.let { id ->
        val target = state.subscriptions.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("購読をやめますか?") },
            text = { Text("「${target?.title.orEmpty()}」の新着を保存しなくなります。これまでに保存したブックマークは残ります。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeTarget = null
                        onRemove(id)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("やめる") }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("キャンセル") } }
        )
    }
}

@Composable
private fun SubscriptionRow(
    subscription: Subscription,
    nowMillis: Long,
    onSetEnabled: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val source = subscription.searchSource
        Icon(
            when {
                subscription.type == SubscriptionType.YOUTUBE -> Icons.Outlined.PlayCircle
                source == null -> Icons.Outlined.RssFeed
                source == SearchSource.GITHUB_RELEASES -> Icons.Outlined.NewReleases
                source.group == SearchGroup.PAPER -> Icons.Outlined.Science
                source.group == SearchGroup.NEWS -> Icons.Outlined.Newspaper
                else -> Icons.Outlined.Code
            },
            contentDescription = source?.label ?: subscription.type.label,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(subscription.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = source?.let { searchSubtitle(it, subscription.queryKind ?: it.kinds.first()) }
                    ?: extractDomain(subscription.siteUrl ?: subscription.feedUrl),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = statusLabel(subscription, nowMillis),
                style = MaterialTheme.typography.labelSmall,
                color = if (subscription.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = subscription.enabled,
            onCheckedChange = onSetEnabled,
            modifier = Modifier.semantics { contentDescription = "「${subscription.title}」を確認する" }
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Delete, contentDescription = "「${subscription.title}」の購読をやめる")
        }
    }
}

/** 購読を追加する入力の種類 */
enum class SubscribeMode(val label: String) {
    /** サイト・フィード・YouTube のチャンネルの URL */
    Site("サイト・YouTube"),

    /** サイトのキーワード・タグなどの検索(論文・ニュース・技術記事・GitHub のリリース) */
    Search("キーワード・タグ")
}

/** 検索の購読の一覧での説明の一行(「arXiv の新着論文(著者)」など) */
internal fun searchSubtitle(source: SearchSource, kind: QueryKind): String {
    if (source == SearchSource.GITHUB_RELEASES) return "GitHub の新しいリリース"
    val noun = if (source.group == SearchGroup.PAPER) "論文" else "記事"
    val by = if (kind == QueryKind.KEYWORDS) "" else "(${kind.label})"
    return "${source.label} の新着$noun$by"
}

/** 検索の購読の入力(サイト・探し方・キーワードなど) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchInput(
    source: SearchSource,
    onSourceChange: (SearchSource) -> Unit,
    kind: QueryKind,
    onKindChange: (QueryKind) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    enabled: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchSource.grouped.forEach { (group, sources) ->
            Text(group.label, style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sources.forEach { item ->
                    FilterChip(
                        selected = source == item,
                        onClick = { onSourceChange(item) },
                        label = { Text(item.label) },
                        enabled = enabled
                    )
                }
            }
        }
        Text(
            source.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (source.kinds.size > 1) {
            Text("探し方", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                source.kinds.forEach { item ->
                    FilterChip(
                        selected = kind == item,
                        onClick = { onKindChange(item) },
                        label = { Text(item.label) },
                        enabled = enabled
                    )
                }
            }
        }
        val hint = queryHint(source, kind)
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(hint.label) },
            placeholder = { Text(hint.example) },
            supportingText = { Text(hint.help) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (kind == QueryKind.REPOSITORY) KeyboardType.Uri else KeyboardType.Text,
                imeAction = ImeAction.Done
            )
        )
    }
}

/** 入力欄の名前・例・説明 */
internal data class QueryHint(val label: String, val example: String, val help: String)

internal fun queryHint(source: SearchSource, kind: QueryKind): QueryHint = when (kind) {
    QueryKind.KEYWORDS -> QueryHint(
        label = "キーワード",
        example = when (source.group) {
            SearchGroup.PAPER -> "例: \"large language model\" agent"
            SearchGroup.NEWS -> "例: 生成AI 規制"
            SearchGroup.TECH -> "例: rust async"
        },
        help = "空白で区切った語をすべて含むものを探します。\"…\" で囲むと語句のまま探します" +
            if (source.group == SearchGroup.PAPER || source == SearchSource.HACKER_NEWS) "。英語の語のほうが多く見つかります" else ""
    )
    QueryKind.AUTHOR -> QueryHint("著者の名前", "例: Yann LeCun", "名前の語をすべて含む著者の新しい論文を探します")
    QueryKind.CATEGORY -> QueryHint(
        "arXiv の分野",
        "例: cs.AI",
        "分野の記号(cs.AI・cs.CL・stat.ML・quant-ph など)。その分野に新しく投稿された論文を保存します(1回に最大 $MAX_NEW_ITEMS_PER_FEED 件)"
    )
    QueryKind.TAG -> QueryHint(
        if (source == SearchSource.ZENN) "トピック" else "タグ",
        "例: kotlin",
        "タグを1つ入れます。そのタグの付いた新しい記事を保存します"
    )
    QueryKind.REPOSITORY -> QueryHint("リポジトリ", "例: JetBrains/kotlin", "owner/repo か、リポジトリの URL を入れます")
}

/** 購読の状態の一行(失敗していればその理由、まだ確認していなければその旨、そうでなければ最後に確認した時間) */
internal fun statusLabel(subscription: Subscription, nowMillis: Long): String = when {
    !subscription.enabled -> "オフ(確認しません)"
    subscription.lastError != null -> "確認できません: ${subscription.lastError}"
    subscription.lastCheckedAt == null -> "まだ確認していません(次の夜間に確認します)"
    else -> "最後の確認: ${formatRelativeTime(subscription.lastCheckedAt, nowMillis)}"
}
