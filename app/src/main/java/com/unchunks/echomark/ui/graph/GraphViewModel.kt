package com.unchunks.echomark.ui.graph

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.graph.ForceLayout
import com.unchunks.echomark.domain.graph.GraphLayout
import com.unchunks.echomark.domain.graph.GraphOptions
import com.unchunks.echomark.domain.graph.KnowledgeGraph
import com.unchunks.echomark.domain.graph.KnowledgeGraphBuilder
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.DuplicateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/** 表示する点の数の選択肢 */
val GRAPH_NODE_CHOICES = listOf(50, KnowledgeGraphBuilder.DEFAULT_MAX_NODES, KnowledgeGraphBuilder.MAX_NODES)

data class GraphUiState(
    /** つながりを探している間・並べている間は true */
    val isLoading: Boolean = true,
    val graph: KnowledgeGraph = KnowledgeGraph.EMPTY,
    val layout: GraphLayout = GraphLayout.EMPTY,
    val selectedId: Long? = null,
    val maxNodes: Int = KnowledgeGraphBuilder.DEFAULT_MAX_NODES,
    val tagFilter: String? = null,
    /** 絞り込みに選べるタグ(付いている数の多い順) */
    val filterTags: List<String> = emptyList(),
    val bookmarkCount: Int = 0,
    /** 検索用の索引が全部できていない(できていない分は、共通のタグでしかつながらない) */
    val indexIncomplete: Boolean = false,
    val errorMessage: String? = null
)

/** 探した結果(関連のまとまりと全部のブックマーク)。点の数・タグの絞り込みを変えても、探し直さずに使い回す。 */
private class Source(
    val bookmarks: List<Bookmark>,
    val groups: List<List<Long>>,
    val indexIncomplete: Boolean
)

/**
 * ナレッジグラフ。関連のまとまり(#81)と共通のタグからつながりを作り、力指向で並べる。
 * つながりを探すのは重い(検索用の索引を全部たどる)ので、開いたときと「探し直す」のときだけ行う。
 * 並べる計算は [DispatcherProvider.default] で行い、点の数や絞り込みを変えたら前の計算は取り消す。
 */
@HiltViewModel
class GraphViewModel @Inject constructor(
    private val duplicateRepository: DuplicateRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val appSettings: AppSettingsRepository,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(GraphUiState())
    val uiState: StateFlow<GraphUiState> = _uiState.asStateFlow()

    private var source: Source? = null
    private var loadJob: Job? = null
    private var layoutJob: Job? = null

    init {
        refresh()
    }

    /** つながりを探し直す。 */
    fun refresh() {
        loadJob?.cancel()
        layoutJob?.cancel()
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        loadJob = viewModelScope.launch {
            try {
                val scan = duplicateRepository.scan(appSettings.duplicateSensitivity.first())
                val bookmarks = bookmarkRepository.observeBookmarks().first()
                val loaded = Source(bookmarks, scan.groups.map { group -> group.bookmarks.map { it.id } }, !scan.isComplete)
                source = loaded
                val tags = bookmarks.flatMap { it.tags.distinct() }.groupingBy { it }.eachCount()
                    .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .take(FILTER_TAG_LIMIT).map { it.key }
                _uiState.update { it.copy(filterTags = tags, bookmarkCount = bookmarks.size, indexIncomplete = loaded.indexIncomplete) }
                rebuild()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "ナレッジグラフの読み込みに失敗")
                _uiState.update { it.copy(isLoading = false, errorMessage = "つながりを調べられませんでした。") }
            }
        }
    }

    fun selectNode(id: Long?) = _uiState.update { it.copy(selectedId = id) }

    fun setMaxNodes(maxNodes: Int) {
        _uiState.update { it.copy(maxNodes = maxNodes) }
        rebuild()
    }

    fun setTagFilter(tag: String?) {
        _uiState.update { it.copy(tagFilter = tag, selectedId = null) }
        rebuild()
    }

    /** いまの条件でグラフを作り直して並べる(探した結果は使い回す)。 */
    private fun rebuild() {
        val loaded = source ?: return
        layoutJob?.cancel()
        _uiState.update { it.copy(isLoading = true) }
        layoutJob = viewModelScope.launch {
            val state = _uiState.value
            try {
                val (graph, layout) = withContext(dispatchers.default) {
                    val graph = KnowledgeGraphBuilder.build(
                        loaded.bookmarks, loaded.groups, GraphOptions(maxNodes = state.maxNodes, tagFilter = state.tagFilter)
                    )
                    graph to ForceLayout.layout(graph, isCancelled = { !isActive })
                }
                _uiState.update { current ->
                    current.copy(
                        isLoading = false, graph = graph, layout = layout,
                        selectedId = current.selectedId?.takeIf { id -> graph.nodes.any { it.id == id } }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "ナレッジグラフを作れなかった")
                _uiState.update { it.copy(isLoading = false, errorMessage = "つながりを並べられませんでした。") }
            }
        }
    }

    companion object {
        /** 絞り込みに出すタグの数 */
        const val FILTER_TAG_LIMIT = 24
    }
}
