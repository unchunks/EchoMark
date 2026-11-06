package com.unchunks.echomark.ui.graph

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.components.composeui.ReadableLazyColumn
import com.unchunks.echomark.domain.graph.EdgeKind
import com.unchunks.echomark.domain.graph.GraphEdge
import com.unchunks.echomark.domain.graph.KnowledgeGraph
import com.unchunks.echomark.ui.components.EmptyState
import com.unchunks.echomark.ui.components.ErrorState
import com.unchunks.echomark.ui.components.LoadingState

/** 画面の 2 つの表示(絵・一覧)。一覧は絵と同じ内容を読み上げでも使えるようにするためのもの。 */
enum class GraphMode { GRAPH, LIST }

/**
 * ナレッジグラフ(ブックマークのつながり)。ViewModel をつなぐだけの薄いラッパー。
 * @param onOpenBookmark 「開く」を押したとき(詳細画面を開く)
 */
@Composable
fun GraphScreen(
    onBack: () -> Unit,
    onOpenBookmark: (Long) -> Unit,
    viewModel: GraphViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    GraphContent(
        uiState = uiState,
        onBack = onBack,
        onOpenBookmark = onOpenBookmark,
        onRefresh = viewModel::refresh,
        onSelect = viewModel::selectNode,
        onMaxNodes = viewModel::setMaxNodes,
        onTagFilter = viewModel::setTagFilter
    )
}

/** 画面の本体(状態を受け取って描くだけ)。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphContent(
    uiState: GraphUiState,
    onBack: () -> Unit,
    onOpenBookmark: (Long) -> Unit,
    onRefresh: () -> Unit,
    onSelect: (Long?) -> Unit,
    onMaxNodes: (Int) -> Unit,
    onTagFilter: (String?) -> Unit,
    modifier: Modifier = Modifier,
    initialMode: GraphMode = GraphMode.GRAPH
) {
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("つながり", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !uiState.isLoading) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "つながりを探し直す")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            Controls(uiState, mode, onMode = { mode = it }, onMaxNodes = onMaxNodes, onTagFilter = onTagFilter)
            val graph = uiState.graph
            when {
                uiState.errorMessage != null -> ErrorState(message = uiState.errorMessage, onRetry = onRefresh)
                uiState.isLoading && graph.nodes.isEmpty() -> LoadingState(message = "つながりを調べています")
                graph.nodes.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Hub,
                    title = if (uiState.tagFilter != null) "このタグには、つながりがありません" else "つながりがまだありません",
                    description = "中身が近いブックマークや、同じタグが付いたブックマークがあると、ここにつながりが出ます。" +
                        if (uiState.indexIncomplete) "検索用の索引がまだ全部できていないため、見つからないものがあるかもしれません。" else ""
                )
                mode == GraphMode.GRAPH -> GraphBody(uiState, onSelect, onOpenBookmark)
                else -> GraphList(uiState, onSelect, onOpenBookmark)
            }
        }
    }
}

@Composable
private fun Controls(
    uiState: GraphUiState,
    mode: GraphMode,
    onMode: (GraphMode) -> Unit,
    onMaxNodes: (Int) -> Unit,
    onTagFilter: (String?) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(selected = mode == GraphMode.GRAPH, onClick = { onMode(GraphMode.GRAPH) }, label = { Text("図") })
            FilterChip(selected = mode == GraphMode.LIST, onClick = { onMode(GraphMode.LIST) }, label = { Text("一覧") })
            Text("表示数", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
            GRAPH_NODE_CHOICES.forEach { choice ->
                FilterChip(selected = uiState.maxNodes == choice, onClick = { onMaxNodes(choice) }, label = { Text("$choice") })
            }
        }
        if (uiState.filterTags.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("タグ", style = MaterialTheme.typography.labelLarge)
                FilterChip(selected = uiState.tagFilter == null, onClick = { onTagFilter(null) }, label = { Text("すべて") })
                uiState.filterTags.forEach { tag ->
                    FilterChip(
                        selected = uiState.tagFilter == tag,
                        onClick = { onTagFilter(if (uiState.tagFilter == tag) null else tag) },
                        label = { Text(tag) }
                    )
                }
            }
        }
        val graph = uiState.graph
        if (graph.nodes.isNotEmpty()) {
            Text(
                buildString {
                    append("${graph.nodes.size}件、つながり${graph.edges.size}本")
                    if (graph.isTruncated) append("(つながりの多い順。つながりのあるブックマークは全部で${graph.candidateCount}件)")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }
    }
}

/** 図と、選んだ点の詳細。実線は中身が近い関連、点線は共通のタグ。 */
@Composable
private fun GraphBody(uiState: GraphUiState, onSelect: (Long?) -> Unit, onOpenBookmark: (Long) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            GraphCanvas(
                graph = uiState.graph,
                layout = uiState.layout,
                selectedId = uiState.selectedId,
                onSelect = onSelect
            )
            if (uiState.isLoading) {
                Text(
                    "並べ直しています",
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Text(
            "実線: 中身が近い  点線: 同じタグ  ピンチで拡大、ドラッグで移動",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val selected = uiState.graph.nodes.indexOfFirst { it.id == uiState.selectedId }
        if (selected >= 0) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(12.dp).heightIn(max = 260.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                NodeDetail(uiState.graph, selected, onSelect, onOpenBookmark, onClose = { onSelect(null) })
            }
        }
    }
}

/** 選んだ点のタイトルと、つながっている相手(タップで相手を選ぶ)。 */
@Composable
private fun NodeDetail(
    graph: KnowledgeGraph,
    index: Int,
    onSelect: (Long?) -> Unit,
    onOpenBookmark: (Long) -> Unit,
    onClose: (() -> Unit)?
) {
    val node = graph.nodes[index]
    val edges = graph.edgesOf(index).sortedByDescending { it.weight }
    Column(Modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                node.bookmark.title,
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            FilledTonalButton(onClick = { onOpenBookmark(node.id) }) { Text("開く") }
            if (onClose != null) TextButton(onClick = onClose) { Text("閉じる") }
        }
        Text(
            "つながり ${edges.size}件",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // 縦に長くなるので、図の下の詳細ではスクロールできるようにする
        Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
            edges.forEach { edge ->
                val otherIndex = if (edge.a == index) edge.b else edge.a
                val other = graph.nodes[otherIndex]
                Column(
                    Modifier.fillMaxWidth().clickable(onClickLabel = "このブックマークを選ぶ") { onSelect(other.id) }.padding(vertical = 6.dp)
                ) {
                    Text(other.bookmark.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        edge.reasonText(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 「中身が近い」「共通のタグ: a、b」など、つながりの理由。 */
internal fun GraphEdge.reasonText(): String = when (kind) {
    EdgeKind.RELATED -> "中身が近い"
    EdgeKind.TAG -> "共通のタグ: ${sharedTags.joinToString("、")}"
    EdgeKind.BOTH -> "中身が近い・共通のタグ: ${sharedTags.joinToString("、")}"
}

/** 図と同じ内容の一覧(まとまりごと)。点をタップすると、つながっている相手を開く。 */
@Composable
private fun GraphList(uiState: GraphUiState, onSelect: (Long?) -> Unit, onOpenBookmark: (Long) -> Unit) {
    val graph = uiState.graph
    ReadableLazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        graph.clusters.forEach { cluster ->
            item(key = "c${cluster.index}", contentType = "cluster") {
                Text(
                    buildString {
                        append(if (cluster.label.isNotEmpty()) cluster.label else "まとまり ${cluster.index + 1}")
                        append("(${cluster.size}件)")
                    },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleSmall
                )
            }
            nodeRows(graph, cluster.index, uiState.selectedId, onSelect, onOpenBookmark)
        }
    }
}

private fun LazyListScope.nodeRows(
    graph: KnowledgeGraph,
    cluster: Int,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onOpenBookmark: (Long) -> Unit
) {
    val indexes = graph.nodes.indices.filter { graph.nodes[it].cluster == cluster }.sortedByDescending { graph.nodes[it].degree }
    indexes.forEach { index ->
        val node = graph.nodes[index]
        item(key = "n${node.id}", contentType = "node") {
            val selected = node.id == selectedId
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow
                ),
                onClick = { onSelect(if (selected) null else node.id) }
            ) {
                if (selected) {
                    NodeDetail(graph, index, onSelect, onOpenBookmark, onClose = null)
                } else {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(node.bookmark.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "つながり ${node.degree}件",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
