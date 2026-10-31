package com.unchunks.echomark.ui.bookmark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.domain.bookmark.model.AttachmentException
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkQuery
import com.unchunks.echomark.domain.bookmark.model.BookmarkListDisplay
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.bookmark.model.CreatedPeriod
import com.unchunks.echomark.domain.bookmark.model.TagMatch
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.ReadState
import com.unchunks.echomark.domain.bookmark.model.ReadingRecommender
import com.unchunks.echomark.domain.bookmark.model.Recommendation
import com.unchunks.echomark.domain.bookmark.model.SearchHistory
import com.unchunks.echomark.domain.bookmark.model.SearchMatch
import com.unchunks.echomark.domain.bookmark.model.SearchMatcher
import com.unchunks.echomark.domain.bookmark.model.SearchSuggester
import com.unchunks.echomark.domain.bookmark.model.SearchSuggestion
import com.unchunks.echomark.domain.bookmark.model.TagCandidate
import com.unchunks.echomark.domain.bookmark.model.splitSearchTerms
import com.unchunks.echomark.domain.model.Tag
import com.unchunks.echomark.domain.model.TagWithCount
import com.unchunks.echomark.domain.rediscover.RediscoverSelector
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.AttachmentRepository
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.SaveResult
import com.unchunks.echomark.domain.repository.CategoryRepository
import com.unchunks.echomark.domain.repository.TagRepository
import com.unchunks.echomark.ui.common.RecentlyDeletedBookmarks
import com.unchunks.echomark.ui.common.SearchHighlights
import com.unchunks.echomark.ui.common.SelectedFile
import com.unchunks.echomark.ui.common.normalizeUrlInput
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BookmarkViewModel @Inject constructor(
    private val repository: BookmarkRepository,
    private val tagRepository: TagRepository,
    private val categoryRepository: CategoryRepository,
    private val recentlyDeleted: RecentlyDeletedBookmarks,
    private val attachmentRepository: AttachmentRepository,
    private val appSettings: AppSettingsRepository,
    private val dispatchers: DispatcherProvider,
    private val searchHighlights: SearchHighlights
) : ViewModel() {

    /** 検索語以外の表示条件 */
    private data class ListControls(
        /** 絞り込み条件(通常/アーカイブ/お気に入り・タグ・カテゴリ) */
        val query: BookmarkQuery = BookmarkQuery(),
        val sortOrder: BookmarkSortOrder = BookmarkSortOrder.NEWEST,
        /** 一覧の見せ方。並び順と同じく設定に保存したものを使う */
        val display: BookmarkListDisplay = BookmarkListDisplay.CARD,
        val searchActive: Boolean = false
    )

    /** 一覧の中身。[query] はこの結果を出した検索語(検索していなければ空) */
    private data class ListResult(
        val bookmarks: List<Bookmark> = emptyList(),
        val query: String = "",
        val semanticAvailable: Boolean = true,
        val semanticMatchIds: Set<Long> = emptySet(),
        /** キーワードの結果だけを出していて、意味検索の準備(検索語の埋め込み)を待っている */
        val semanticPending: Boolean = false,
        /** キーワードで一致した場所(ブックマークの ID → 一致した場所と本文の一節) */
        val matches: Map<Long, SearchMatch> = emptyMap(),
        val error: String? = null
    )

    private val searchQueryFlow = MutableStateFlow("")
    private val controlsFlow = MutableStateFlow(ListControls())

    /**
     * 表示に使う条件。並び順は設定(DataStore)に保存したものを使い、アプリを開き直しても選んだ順を保つ
     * (ViewModel だけで持つと、アプリを終了したりプロセスが破棄されたりすると新しい順に戻ってしまう)。
     * 保存した並び順を読むまでは一覧を出さない(既定の順で一瞬出してから並べ替わらないように)
     */
    private val shownControlsFlow: Flow<ListControls> =
        combine(controlsFlow, appSettings.bookmarkSortOrder, appSettings.bookmarkListDisplay) { controls, sortOrder, display ->
            controls.copy(sortOrder = sortOrder, display = display)
        }

    private val retryTrigger = MutableStateFlow(0)
    private val rediscoverFlow = MutableStateFlow<List<Bookmark>>(emptyList())

    /** 「次に読む」(#61)。未読と最近読んだものが変わるたびに選び直す。補助的な表示なので、取れなくても一覧は出す */
    private val recommendationsFlow: Flow<List<Recommendation>> = combine(
        repository.observeUnreadCandidates(RECOMMEND_CANDIDATES),
        repository.observeRecentlyRead(RECOMMEND_HISTORY)
    ) { unread, read -> ReadingRecommender.pick(unread, read, System.currentTimeMillis()) }
        .flowOn(dispatchers.default)
        .catch { e ->
            if (e is CancellationException) throw e
            Timber.w(e, "次に読むものを選べませんでした")
            emit(emptyList())
        }

    private val messageChannel = Channel<BookmarkListMessage>(Channel.BUFFERED)
    private val addSheetRequestChannel = Channel<Unit>(Channel.CONFLATED)

    /** Snackbar で知らせる出来事。画面が表示されている間に1回ずつ受け取る */
    val messages: Flow<BookmarkListMessage> = messageChannel.receiveAsFlow()

    /** 追加シートを開く要求(ショートカット・ウィジェットの「URL を追加」から起動したとき)。画面が1回ずつ受け取る */
    val addSheetRequests: Flow<Unit> = addSheetRequestChannel.receiveAsFlow()

    /** 入力が空なら即時、入力中は 300ms のデバウンスを掛けて検索実行を間引く */
    @OptIn(FlowPreview::class)
    private val debouncedQueryFlow: Flow<String> = searchQueryFlow
        .map { it.trim() }
        .debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS }
        .distinctUntilChanged()

    private val listFlow: Flow<ListResult> =
        combine(debouncedQueryFlow, shownControlsFlow, retryTrigger) { query, controls, _ -> query to controls }
            .flatMapLatest { (query, controls) ->
                // 絞り込み・並べ替え済みの一覧。検索時は「表示してよいもの」の判定(検索結果との積)と、DB 変更時の再検索のきっかけに使う。
                // 検索自体はタグ・カテゴリを知らない(絞り込みの条件はここだけで持つ)
                val visible = repository.observeBookmarks(controls.query, controls.sortOrder)
                val result = if (controls.searchActive && query.isNotBlank()) {
                    // 検索語の埋め込み(意味検索の準備)は、モデルの読み込みや AI 処理との順番待ちで時間がかかることがある。
                    // 待つ間はキーワードの結果を先に出す。準備は検索語ごとに1回だけで、DB の変更で検索し直しても待ち直さない
                    // (null: 準備中、true: 意味検索も使える、false: 埋め込みが使えずキーワードのみ)
                    // 入力が落ち着くのを少し待ってから始める(1文字ごとに埋め込むと、AI 処理の邪魔になる)
                    val semanticReady = flow {
                        emit(null)
                        delay(SEMANTIC_SETTLE_MS)
                        emit(repository.prepareSemanticSearch(query))
                    }
                    combine(visible, semanticReady, ::Pair).mapLatest { (list, ready) ->
                        val allowed = list.map { it.id }.toSet()
                        if (ready == true) {
                            val found = repository.searchWithDetails(query)
                            val shown = found.bookmarks.filter { it.id in allowed }
                            ListResult(
                                bookmarks = shown,
                                query = query,
                                semanticAvailable = found.semanticAvailable,
                                semanticMatchIds = found.semanticOnlyIds,
                                matches = explain(shown, query)
                            )
                        } else {
                            val shown = repository.searchKeywordOnly(query).filter { it.id in allowed }
                            ListResult(
                                bookmarks = shown,
                                query = query,
                                semanticAvailable = ready == null,
                                semanticPending = ready == null,
                                matches = explain(shown, query)
                            )
                        }
                    }
                } else {
                    visible.map { ListResult(bookmarks = it) }
                }
                result.catch { e ->
                    if (e is CancellationException) throw e
                    Timber.w(e, "ブックマーク一覧の読み込みに失敗")
                    emit(ListResult(query = query, error = "ブックマークを読み込めませんでした。もう一度お試しください。"))
                }
            }

    /** 検索結果それぞれが、どこに一致したか(#54)。本文が長いことがあるので、メインスレッドの外で調べる */
    private suspend fun explain(bookmarks: List<Bookmark>, query: String): Map<Long, SearchMatch> {
        val terms = splitSearchTerms(query)
        return withContext(dispatchers.default) {
            bookmarks.associate { it.id to SearchMatcher.explain(it, terms) }
        }
    }

    /**
     * 絞り込みチップに出すタグの元(件数つき・全タグ)。読み込めなかったときは null(選択中のタグを整理せず、そのまま出す)
     */
    private val tagCountsFlow: Flow<List<TagWithCount>?> = tagRepository.observeTagsWithCount()
        .map<List<TagWithCount>, List<TagWithCount>?> { it }
        .catch { e ->
            if (e is CancellationException) throw e
            emit(null)
        }

    /** 絞り込みチップに出すカテゴリ。ブックマークがあるものだけ(選択中のものは、画面を組み立てるときに足す) */
    private val categoryNamesFlow: Flow<List<String>> = categoryRepository.observeCategories()
        .map { list -> list.filter { it.bookmarkCount > 0 }.map { it.name } }
        .catch { e ->
            if (e is CancellationException) throw e
            emit(emptyList())
        }

    /** 検索の履歴。補助的な表示なので、読めなければ空として扱う */
    private val searchHistoryFlow: Flow<List<String>> = appSettings.searchHistory
        .catch { e ->
            if (e is CancellationException) throw e
            emit(emptyList())
        }

    /** 検索語を ViewModel の側から書き換えた回数(候補を選んだとき)。検索欄は、この数が変わったら入力中の文字を取り込み直す */
    private val queryRevisionFlow = MutableStateFlow(0)

    private data class ListInputs(
        val list: ListResult,
        val rawQuery: String,
        val controls: ListControls,
        val history: List<String>,
        val queryRevision: Int
    )

    val uiState: StateFlow<BookmarkListUiState> = combine(
        combine(listFlow, searchQueryFlow, shownControlsFlow, searchHistoryFlow, queryRevisionFlow, ::ListInputs),
        tagCountsFlow,
        categoryNamesFlow,
        combine(rediscoverFlow, recommendationsFlow, ::Pair),
        repository.observeBookmarkCount()
    ) { (list, rawQuery, controls, history, revision), tagCounts, categoryNames, (rediscoverAll, recommendations), total ->
        // 「次に読む」に出したものは、「今日の再発見」には出さない(同じカードを2回見せない)
        val recommendedIds = recommendations.mapTo(HashSet()) { it.bookmark.id }
        val rediscover = rediscoverAll.filter { it.id !in recommendedIds }
        val query = controls.query
        // 削除されたタグは選択から外して見せる(DB の絞り込みも、存在しないタグは無視する)
        val knownTagIds = tagCounts?.mapTo(HashSet()) { it.id }
        val selectedTagIds = if (knownTagIds == null) query.tagIds else query.tagIds.filterTo(LinkedHashSet()) { it in knownTagIds }
        val tags = tagCounts.orEmpty()
            .filter { it.bookmarkCount > 0 || it.id in selectedTagIds }
            .map { Tag(it.id, it.name) }
            .sortedByDescending { it.id in selectedTagIds }
        val categories = (categoryNames + query.categories.filter { selected -> categoryNames.none { it.equals(selected, ignoreCase = true) } })
            .sortedByDescending { name -> query.categories.any { it.equals(name, ignoreCase = true) } }
        val trimmed = rawQuery.trim()
        // 入力中(結果がまだ今の検索語のものではない)か、キーワードの結果を出して意味検索を待っている
        val searching = controls.searchActive && trimmed.isNotEmpty() && (list.query != trimmed || list.semanticPending)
        BookmarkListUiState(
            bookmarks = list.bookmarks,
            errorMessage = list.error,
            allTags = tags,
            selectedTagIds = selectedTagIds,
            tagMatch = query.tagMatch,
            allCategories = categories,
            selectedCategories = query.categories,
            selectedTypes = query.types,
            period = query.period,
            readState = query.readState,
            filter = query.filter,
            sortOrder = controls.sortOrder,
            display = controls.display,
            isSearchActive = controls.searchActive,
            searchQuery = rawQuery,
            queryRevision = revision,
            suggestions = if (!controls.searchActive) emptyList() else SearchSuggester.suggest(
                input = rawQuery,
                history = history,
                tags = tagCounts.orEmpty().map { TagCandidate(it.id, it.name, it.bookmarkCount) },
                categories = categoryNames,
                selectedTagIds = selectedTagIds,
                selectedCategories = query.categories
            ),
            searchTerms = if (controls.searchActive) splitSearchTerms(list.query) else emptyList(),
            searchMatches = if (controls.searchActive) list.matches else emptyMap(),
            isSearching = searching,
            semanticSearchAvailable = list.semanticAvailable,
            semanticMatchIds = if (controls.searchActive) list.semanticMatchIds else emptySet(),
            rediscover = rediscover,
            recommendations = recommendations,
            totalCount = total
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        BookmarkListUiState(isLoading = true)
    )

    init {
        loadRediscover()
        // 詳細画面が本文の検索語を強調できるよう、今の検索語を知らせる(検索を閉じたら空)
        viewModelScope.launch {
            combine(debouncedQueryFlow, controlsFlow) { query, controls -> if (controls.searchActive) splitSearchTerms(query) else emptyList() }
                .distinctUntilChanged()
                .collect { searchHighlights.setTerms(it) }
        }
        // 詳細画面で削除されたものを受け取り、「元に戻す」を出す
        viewModelScope.launch {
            recentlyDeleted.pending.filterNotNull().collect {
                recentlyDeleted.consume()?.let { deleted ->
                    removeFromRediscover(deleted.id)
                    messageChannel.send(BookmarkListMessage.Deleted(deleted))
                }
            }
        }
    }

    private fun loadRediscover() {
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val threshold = now - TimeUnit.DAYS.toMillis(RediscoverSelector.IN_APP_STALE_DAYS)
                val candidates = repository.getStaleBookmarks(threshold, REDISCOVER_CANDIDATES)
                rediscoverFlow.value = RediscoverSelector.pickDaily(
                    candidates,
                    now = now,
                    dayIndex = TimeUnit.MILLISECONDS.toDays(now)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 再発見は補助的な表示なので、失敗しても一覧は出す
                Timber.w(e, "再発見の候補を取得できませんでした")
            }
        }
    }

    private fun removeFromRediscover(id: Long) {
        rediscoverFlow.update { list -> list.filterNot { it.id == id } }
    }

    // ---- 検索・絞り込み・並べ替え ----

    fun onSearchActiveChange(active: Boolean) {
        controlsFlow.update { it.copy(searchActive = active) }
        if (!active) searchQueryFlow.value = ""
    }

    fun onSearchQueryChange(query: String) {
        if (!controlsFlow.value.searchActive) controlsFlow.update { it.copy(searchActive = true) }
        searchQueryFlow.value = query
    }

    /** 一覧のブックマークを開く。検索結果から開いたときは、詳細画面で検索語を強調する */
    fun onOpenBookmark(id: Long) {
        searchHighlights.open(id)
        // 結果を開いたなら、その検索語は探せていた。履歴に残す
        if (controlsFlow.value.searchActive) recordSearch(searchQueryFlow.value)
    }

    /** 検索欄で検索の操作(キーボードの検索ボタン)をした。その検索語を履歴に残す */
    fun onSearchSubmitted() {
        recordSearch(searchQueryFlow.value)
    }

    /** 候補を選んだ(#55)。履歴なら、その検索語で検索する。タグ・カテゴリなら、入力中の語をその絞り込みに置き換える */
    fun onSuggestionSelected(suggestion: SearchSuggestion) {
        when (suggestion) {
            is SearchSuggestion.History -> {
                setSearchQuery(suggestion.query)
                recordSearch(suggestion.query)
            }
            is SearchSuggestion.Tag -> {
                setSearchQuery(searchQueryWithoutLastTerm())
                if (suggestion.id !in controlsFlow.value.query.tagIds) onTagToggled(suggestion.id)
            }
            is SearchSuggestion.Category -> {
                setSearchQuery(searchQueryWithoutLastTerm())
                if (controlsFlow.value.query.categories.none { it.equals(suggestion.name, ignoreCase = true) }) {
                    onCategoryToggled(suggestion.name)
                }
            }
        }
    }

    /** 履歴の1件を消す */
    fun onHistoryRemoved(query: String) {
        updateSearchHistory { SearchHistory.remove(it, query) }
    }

    /** 履歴をすべて消す */
    fun onHistoryCleared() {
        updateSearchHistory { emptyList() }
    }

    private fun searchQueryWithoutLastTerm(): String =
        SearchSuggester.withoutLastTerm(searchQueryFlow.value).let { if (it.isEmpty()) it else "$it " }

    private fun setSearchQuery(query: String) {
        searchQueryFlow.value = query
        queryRevisionFlow.update { it + 1 }
    }

    private fun recordSearch(query: String) {
        updateSearchHistory { SearchHistory.add(it, query) }
    }

    private fun updateSearchHistory(transform: (List<String>) -> List<String>) {
        viewModelScope.launch {
            try {
                appSettings.updateSearchHistory(transform)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 履歴は補助的なものなので、残せなくても検索は続ける
                Timber.w(e, "検索の履歴を更新できませんでした")
            }
        }
    }

    /** タグを選ぶ・外す(複数選べる。組み合わせ方は [onTagMatchChange]) */
    fun onTagToggled(tagId: Long) {
        controlsFlow.update { it.copy(query = it.query.toggleTag(tagId)) }
    }

    /** カテゴリを選ぶ・外す(複数選べて、どれかであればよい) */
    fun onCategoryToggled(name: String) {
        controlsFlow.update { it.copy(query = it.query.toggleCategory(name)) }
    }

    /** 種類(リンク・メモ・画像など)を選ぶ・外す(複数選べて、どれかであればよい) */
    fun onTypeToggled(type: BookmarkType) {
        controlsFlow.update { it.copy(query = it.query.toggleType(type)) }
    }

    /** 保存した日からの期間を選ぶ(選んでいる期間をもう一度選ぶと外れる) */
    fun onPeriodToggled(period: CreatedPeriod) {
        controlsFlow.update { it.copy(query = it.query.togglePeriod(period)) }
    }

    /** 読んだかどうかで絞る(選んでいるものをもう一度選ぶと外れる) */
    fun onReadStateToggled(state: ReadState) {
        controlsFlow.update { it.copy(query = it.query.toggleReadState(state)) }
    }

    /** 選んでいる種類・期間・読んだかを外す(タグ・カテゴリは変えない) */
    fun onClearTypeAndPeriod() {
        controlsFlow.update { it.copy(query = it.query.copy(types = emptySet(), period = null, readState = null)) }
    }

    /** 複数のタグを選んだときの組み合わせ方(すべて含む / どれかを含む)を変える */
    fun onTagMatchChange(match: TagMatch) {
        controlsFlow.update { it.copy(query = it.query.copy(tagMatch = match)) }
    }

    /** 選んでいるタグ・カテゴリ・種類・期間をすべて外す(通常/お気に入り/アーカイブと検索は変えない) */
    fun onClearFilters() {
        controlsFlow.update { it.copy(query = it.query.clearConditions()) }
    }

    /** タグ管理画面で選ばれたタグで絞り込む(それまでのタグ・カテゴリの条件は置き換える)。検索モードは閉じて、そのタグの一覧を見せる */
    fun showTag(tagId: Long) {
        controlsFlow.update { it.copy(query = it.query.clearConditions().copy(tagIds = setOf(tagId)), searchActive = false) }
        searchQueryFlow.value = ""
    }

    /** ショートカット・ウィジェットから起動したとき、追加シートや検索モードを開く */
    fun handleLaunchAction(action: ListLaunchAction) {
        when (action) {
            ListLaunchAction.ADD_BOOKMARK -> addSheetRequestChannel.trySend(Unit)
            ListLaunchAction.SEARCH -> onSearchActiveChange(true)
        }
    }

    fun onFilterChange(filter: BookmarkFilter) {
        controlsFlow.update { it.copy(query = it.query.copy(filter = filter)) }
    }

    /** 並び順を変えて保存する(一覧は保存した値から読み直す) */
    fun onSortOrderChange(sortOrder: BookmarkSortOrder) {
        viewModelScope.launch {
            try {
                appSettings.setBookmarkSortOrder(sortOrder)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "並び順を保存できませんでした")
            }
        }
    }

    /** 一覧の見せ方を変えて保存する(一覧は保存した値から読み直す) */
    fun onDisplayChange(display: BookmarkListDisplay) {
        viewModelScope.launch {
            try {
                appSettings.setBookmarkListDisplay(display)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "一覧の見せ方を保存できませんでした")
            }
        }
    }

    fun retry() {
        retryTrigger.update { it + 1 }
    }

    // ---- 1件への操作 ----

    fun toggleFavorite(bookmark: Bookmark) {
        viewModelScope.launch { repository.setFavorite(bookmark.id, !bookmark.isFavorite) }
    }

    /** 読んだことにする / 未読に戻す(#60) */
    fun toggleRead(bookmark: Bookmark) {
        viewModelScope.launch { repository.setRead(bookmark.id, !bookmark.isRead) }
    }

    /** アーカイブする/戻す。Snackbar で取り消せるよう知らせる */
    fun setArchived(bookmark: Bookmark, archived: Boolean) {
        viewModelScope.launch {
            repository.setArchived(bookmark.id, archived)
            if (archived) removeFromRediscover(bookmark.id)
            messageChannel.send(BookmarkListMessage.ArchiveChanged(bookmark, archived))
        }
    }

    /** アーカイブ操作の取り消し */
    fun undoArchive(bookmark: Bookmark) {
        viewModelScope.launch { repository.setArchived(bookmark.id, bookmark.isArchived) }
    }

    /** 削除する。Snackbar の「元に戻す」で [restoreBookmark] できるよう知らせる */
    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            repository.deleteBookmark(bookmark)
            removeFromRediscover(bookmark.id)
            messageChannel.send(BookmarkListMessage.Deleted(bookmark))
        }
    }

    /** 削除の取り消し。同じ ID・タグで復元し、埋め込みを再生成する */
    fun restoreBookmark(bookmark: Bookmark) {
        viewModelScope.launch { repository.restoreBookmark(bookmark) }
    }

    // ---- 追加 ----

    /** 追加シートの内容を保存し、結果を [messages] で知らせる */
    fun save(input: NewBookmarkInput) {
        when (input) {
            is NewBookmarkInput.Link -> {
                val url = normalizeUrlInput(input.url)
                if (url == null) {
                    messageChannel.trySend(BookmarkListMessage.SaveFailed("URL の形式が正しくありません"))
                    return
                }
                saveUrlBookmark(url, input.title, input.memo)
            }
            is NewBookmarkInput.Note -> {
                val text = input.text.trim()
                if (text.isEmpty()) return
                val title = input.title.trim().ifEmpty { deriveTitle(text) }
                saveTextBookmark(title, text)
            }
            is NewBookmarkInput.Files -> saveFiles(input.files, input.title)
        }
    }

    /**
     * ファイルを1件ずつアプリ内へコピーしてブックマークにする。保存できなかったものがあっても残りは続け、
     * 結果をまとめて知らせる(1件なら「開く」を出せるよう ID も)。
     */
    private fun saveFiles(files: List<SelectedFile>, title: String) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            messageChannel.send(BookmarkListMessage.SavingFiles(files.size))
            var firstId: Long? = null
            var saved = 0
            var firstError: String? = null
            for (file in files) {
                try {
                    val stored = attachmentRepository.importFile(file.uri)
                    val result = repository.saveFileBookmark(stored, title.takeIf { files.size == 1 && it.isNotBlank() }, memo = null)
                    if (firstId == null) firstId = result.id
                    saved++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AttachmentException) {
                    firstError = firstError ?: e.error.userMessage
                } catch (e: Exception) {
                    Timber.w(e, "ファイルの保存に失敗")
                    firstError = firstError ?: "保存できませんでした。もう一度お試しください。"
                }
            }
            val id = firstId
            messageChannel.send(
                if (id == null) {
                    BookmarkListMessage.SaveFailed(firstError ?: "保存できませんでした。もう一度お試しください。")
                } else {
                    BookmarkListMessage.FilesSaved(id, saved, files.size - saved)
                }
            )
        }
    }

    /** テキストのブックマークを保存する。本文は任意。 */
    fun saveTextBookmark(title: String, content: String?, onSaved: (SaveResult) -> Unit = {}) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            runSave(isLink = false, onSaved) {
                repository.saveBookmark(
                    Bookmark(
                        type = BookmarkType.TEXT,
                        content = content?.takeIf { it.isNotBlank() },
                        title = title.trim(),
                        createdAt = now,
                        lastAccessedAt = now,
                        // 自分で書いたメモは、読んだものとして始める(未読の一覧に出さない)
                        readAt = now
                    )
                )
            }
        }
    }

    /** URLのブックマークを保存する。同一URLが保存済みなら既存を再利用する(SaveResult.isDuplicate)。 */
    fun saveUrlBookmark(url: String, title: String?, memo: String?, onSaved: (SaveResult) -> Unit = {}) {
        if (url.isBlank()) return
        viewModelScope.launch {
            runSave(isLink = true, onSaved) { repository.saveUrlBookmark(url, title, memo) }
        }
    }

    private suspend fun runSave(isLink: Boolean, onSaved: (SaveResult) -> Unit, save: suspend () -> SaveResult) {
        val result = try {
            save()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ブックマークの保存に失敗")
            messageChannel.send(BookmarkListMessage.SaveFailed("保存できませんでした。もう一度お試しください。"))
            return
        }
        onSaved(result)
        messageChannel.send(BookmarkListMessage.Saved(result.id, result.isDuplicate, isLink))
    }

    companion object {
        /** 入力が止まってからキーワード検索を始めるまでの待ち。ローカルの検索は軽いので短く、打ちながら結果が変わっていく */
        private const val SEARCH_DEBOUNCE_MS = 120L

        /** キーワードの結果を出してから、意味検索(検索語の埋め込み)を始めるまで入力が落ち着くのを待つ時間 */
        private const val SEMANTIC_SETTLE_MS = 300L
        private const val REDISCOVER_CANDIDATES = 30

        /** 「次に読む」を選ぶ元にする未読の数と、興味の手がかりにする最近読んだものの数 */
        private const val RECOMMEND_CANDIDATES = 60
        private const val RECOMMEND_HISTORY = 20
        private const val DERIVED_TITLE_MAX = 40

        /** 本文の1行目からタイトルを作る(長すぎれば切って「…」) */
        internal fun deriveTitle(text: String): String {
            val firstLine = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            return if (firstLine.length <= DERIVED_TITLE_MAX) firstLine else firstLine.take(DERIVED_TITLE_MAX) + "…"
        }
    }
}

/** ショートカット・ウィジェットから一覧を開いたときに行う操作。 */
enum class ListLaunchAction {
    /** 追加シートを開く */
    ADD_BOOKMARK,

    /** 検索モードにする */
    SEARCH
}
