package com.unchunks.echomark.ui.bookmark

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.model.Tag
import com.unchunks.echomark.domain.rediscover.RediscoverSelector
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.SaveResult
import com.unchunks.echomark.domain.repository.TagRepository
import com.unchunks.echomark.ui.common.RecentlyDeletedBookmarks
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BookmarkViewModel @Inject constructor(
    private val repository: BookmarkRepository,
    private val tagRepository: TagRepository,
    private val recentlyDeleted: RecentlyDeletedBookmarks,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** 検索語以外の表示条件 */
    private data class ListControls(
        val filter: BookmarkFilter = BookmarkFilter.ACTIVE,
        val sortOrder: BookmarkSortOrder = BookmarkSortOrder.NEWEST,
        val tagId: Long? = null,
        val searchActive: Boolean = false
    )

    /** 一覧の中身。[query] はこの結果を出した検索語(検索していなければ空) */
    private data class ListResult(
        val bookmarks: List<Bookmark> = emptyList(),
        val query: String = "",
        val semanticAvailable: Boolean = true,
        val semanticMatchIds: Set<Long> = emptySet(),
        val error: String? = null
    )

    private val searchQueryFlow = MutableStateFlow("")
    private val controlsFlow = MutableStateFlow(ListControls())
    private val retryTrigger = MutableStateFlow(0)
    private val rediscoverFlow = MutableStateFlow<List<Bookmark>>(emptyList())

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
        combine(debouncedQueryFlow, controlsFlow, retryTrigger) { query, controls, _ -> query to controls }
            .flatMapLatest { (query, controls) ->
                // 絞り込み・並べ替え済みの一覧。検索時は「表示してよいもの」の判定と、DB 変更時の再検索のきっかけに使う
                val visible = repository.observeBookmarks(controls.filter, controls.sortOrder, controls.tagId)
                val result = if (controls.searchActive && query.isNotBlank()) {
                    visible.mapLatest { list ->
                        val allowed = list.map { it.id }.toSet()
                        val found = repository.searchWithDetails(query, controls.tagId)
                        ListResult(
                            bookmarks = found.bookmarks.filter { it.id in allowed },
                            query = query,
                            semanticAvailable = found.semanticAvailable,
                            semanticMatchIds = found.semanticOnlyIds
                        )
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

    /** 絞り込みチップに出すタグ。ブックマークが付いているもの(と選択中のもの)だけ */
    private val tagsFlow: Flow<List<Tag>> = tagRepository.observeTagsWithCount()
        .map { list -> list.filter { it.bookmarkCount > 0 }.map { Tag(it.id, it.name) } }
        .catch { e ->
            if (e is CancellationException) throw e
            emit(emptyList())
        }

    val uiState: StateFlow<BookmarkListUiState> = combine(
        combine(listFlow, searchQueryFlow, controlsFlow, ::Triple),
        tagsFlow,
        rediscoverFlow,
        repository.observeBookmarkCount()
    ) { (list, rawQuery, controls), tags, rediscover, total ->
        val trimmed = rawQuery.trim()
        val searching = controls.searchActive && trimmed.isNotEmpty() && list.query != trimmed
        BookmarkListUiState(
            bookmarks = list.bookmarks,
            errorMessage = list.error,
            allTags = tags.sortedByDescending { it.id == controls.tagId },
            selectedTagId = controls.tagId,
            filter = controls.filter,
            sortOrder = controls.sortOrder,
            isSearchActive = controls.searchActive,
            searchQuery = rawQuery,
            isSearching = searching,
            semanticSearchAvailable = list.semanticAvailable,
            semanticMatchIds = if (controls.searchActive) list.semanticMatchIds else emptySet(),
            rediscover = rediscover,
            totalCount = total
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        BookmarkListUiState(isLoading = true)
    )

    init {
        loadRediscover()
        // 詳細画面で削除されたものを受け取り、「元に戻す」を出す
        viewModelScope.launch {
            recentlyDeleted.pending.filterNotNull().collect {
                recentlyDeleted.consume()?.let { deleted ->
                    removeFromRediscover(deleted.id)
                    messageChannel.send(BookmarkListMessage.Deleted(deleted))
                }
            }
        }
        // タグ管理画面でタグを選んで戻ってきたら、そのタグで絞り込む
        viewModelScope.launch {
            savedStateHandle.getStateFlow(KEY_SELECT_TAG_ID, NO_TAG).collect { tagId ->
                if (tagId != NO_TAG) {
                    controlsFlow.update { it.copy(tagId = tagId, searchActive = false) }
                    searchQueryFlow.value = ""
                    savedStateHandle[KEY_SELECT_TAG_ID] = NO_TAG
                }
            }
        }
        // ショートカット・ウィジェットから起動したとき、追加シートや検索モードを開く
        viewModelScope.launch {
            savedStateHandle.getStateFlow<String?>(KEY_LAUNCH_ACTION, null).collect { name ->
                val action = ListLaunchAction.entries.firstOrNull { it.name == name } ?: return@collect
                savedStateHandle[KEY_LAUNCH_ACTION] = null
                when (action) {
                    ListLaunchAction.ADD_BOOKMARK -> addSheetRequestChannel.send(Unit)
                    ListLaunchAction.SEARCH -> onSearchActiveChange(true)
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

    fun onTagSelected(tagId: Long?) {
        controlsFlow.update { it.copy(tagId = tagId) }
    }

    fun onFilterChange(filter: BookmarkFilter) {
        controlsFlow.update { it.copy(filter = filter) }
    }

    fun onSortOrderChange(sortOrder: BookmarkSortOrder) {
        controlsFlow.update { it.copy(sortOrder = sortOrder) }
    }

    fun retry() {
        retryTrigger.update { it + 1 }
    }

    // ---- 1件への操作 ----

    fun toggleFavorite(bookmark: Bookmark) {
        viewModelScope.launch { repository.setFavorite(bookmark.id, !bookmark.isFavorite) }
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
        }
    }

    /** テキストのブックマークを保存する。本文は任意。 */
    fun saveTextBookmark(title: String, content: String?, onSaved: (SaveResult) -> Unit = {}) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            runSave(isLink = false, onSaved) {
                repository.saveBookmarkWithResult(
                    Bookmark(
                        type = BookmarkType.TEXT,
                        content = content?.takeIf { it.isNotBlank() },
                        title = title.trim(),
                        createdAt = now,
                        lastAccessedAt = now
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
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val REDISCOVER_CANDIDATES = 30
        private const val DERIVED_TITLE_MAX = 40

        /** タグ管理画面から「このタグで絞り込む」ときに、一覧の SavedStateHandle に入れるキー */
        const val KEY_SELECT_TAG_ID = "selectTagId"
        private const val NO_TAG = -1L

        /** 起動直後の操作([ListLaunchAction] の name)を、一覧の SavedStateHandle に入れるキー */
        const val KEY_LAUNCH_ACTION = "launchAction"

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
