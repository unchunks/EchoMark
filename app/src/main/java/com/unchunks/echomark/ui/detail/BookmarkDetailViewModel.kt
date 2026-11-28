package com.unchunks.echomark.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkCollection
import com.unchunks.echomark.domain.bookmark.model.BookmarkNote
import com.unchunks.echomark.domain.bookmark.model.CollectionNames
import com.unchunks.echomark.domain.bookmark.model.CollectionResult
import com.unchunks.echomark.domain.export.ExportException
import com.unchunks.echomark.domain.export.ExportFormat
import com.unchunks.echomark.domain.export.ExportResult
import com.unchunks.echomark.domain.linkcheck.LinkCheckState
import com.unchunks.echomark.domain.bookmark.model.ReadingStyle
import com.unchunks.echomark.domain.bookmark.model.StageDurationKey
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.model.SummaryLanguage
import com.unchunks.echomark.domain.model.CategoryEntry
import com.unchunks.echomark.domain.model.SummaryVersion
import com.unchunks.echomark.domain.provider.SummaryTranslator
import com.unchunks.echomark.domain.provider.toLlmUserMessage
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.CategoryRepository
import com.unchunks.echomark.domain.repository.CollectionRepository
import com.unchunks.echomark.domain.repository.ExportRepository
import com.unchunks.echomark.domain.repository.LinkCheckRepository
import com.unchunks.echomark.domain.repository.NoteRepository
import com.unchunks.echomark.domain.repository.ProcessingStatsRepository
import com.unchunks.echomark.ui.common.RecentlyDeletedBookmarks
import com.unchunks.echomark.ui.common.SearchHighlights
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class BookmarkDetailUiState(
    val isLoading: Boolean = true,
    /** null かつ isLoading=false なら該当ブックマークなし(削除済みなど) */
    val bookmark: Bookmark? = null,
    val related: List<Bookmark> = emptyList(),
    /** タグ入力の候補にする既存のタグ名 */
    val allTagNames: List<String> = emptyList(),
    /** 実行中の段の進み具合(0〜1)。段が報告していなければ null */
    val stageProgress: Float? = null,
    /** 段・中身の種類ごとの平均の所要時間(残り時間の目安に使う) */
    val stageAverages: Map<StageDurationKey, Long> = emptyMap(),
    /** 要約の履歴(新しい版から先に。#21) */
    val summaryHistory: List<SummaryVersion> = emptyList(),
    /** 要約の翻訳(#24) */
    val translation: TranslationState = TranslationState.Idle,
    /** カテゴリの変更で選べるカテゴリ(自分のもの → 標準のもの。#33) */
    val categoryChoices: List<CategoryEntry> = emptyList(),
    /** 検索結果から開いたときの検索語。タイトル・要約・本文で一致した文字を強調する(#57)。空なら強調しない */
    val highlightTerms: List<String> = emptyList(),
    /** 読書モードの見た目(文字の大きさ・背景・書体。#59) */
    val readingStyle: ReadingStyle = ReadingStyle(),
    /** 自分のノート・注釈(書いた順。#95)。AI の要約・本文とは別 */
    val notes: List<BookmarkNote> = emptyList(),
    /** 作ってあるコレクション(追加のシートに出す。#91) */
    val collections: List<BookmarkCollection> = emptyList(),
    /** このブックマークが入っているコレクションの ID */
    val collectionIds: Set<Long> = emptySet(),
    /** リンク切れの確認の状態(#94)。まだ確認していなければ null */
    val linkState: LinkCheckState? = null,
    /** リンクを今すぐ確認している */
    val linkChecking: Boolean = false
)

/** 要約の翻訳の状態(#24)。翻訳は保存せず、画面を閉じたら消える。 */
sealed interface TranslationState {
    data object Idle : TranslationState

    data class Loading(val target: SummaryLanguage) : TranslationState

    data class Done(val target: SummaryLanguage, val text: String) : TranslationState

    data class Failed(val target: SummaryLanguage, val message: String) : TranslationState
}

/** 詳細画面で一度だけ伝える出来事(Snackbar で知らせる)。 */
sealed interface BookmarkDetailMessage {
    /** アーカイブした/戻した(元に戻せる) */
    data class ArchiveChanged(val archived: Boolean) : BookmarkDetailMessage

    /** タイトル・本文を編集した(AI の再処理を提案する) */
    data object Edited : BookmarkDetailMessage

    /** タグを外した(元に戻せる) */
    data class TagRemoved(val name: String) : BookmarkDetailMessage

    /** ノートを削除した(元に戻せる) */
    data class NoteDeleted(val note: BookmarkNote) : BookmarkDetailMessage

    /** コレクションに入れた・外した */
    data class CollectionChanged(val name: String, val added: Boolean) : BookmarkDetailMessage

    /** ファイルに書き出した */
    data class Exported(val result: ExportResult) : BookmarkDetailMessage

    /** リンクを確認した。[state] は結果(通信できなかったときは null) */
    data class LinkChecked(val state: LinkCheckState?) : BookmarkDetailMessage

    /** AI の再処理を始めた */
    data object ReprocessStarted : BookmarkDetailMessage

    /** 要約を手で直した */
    data object SummaryEdited : BookmarkDetailMessage

    /** 履歴の版に要約を戻した */
    data object SummaryRestored : BookmarkDetailMessage

    /** 操作に失敗した */
    data class Failed(val message: String) : BookmarkDetailMessage
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BookmarkDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: BookmarkRepository,
    private val recentlyDeleted: RecentlyDeletedBookmarks,
    processingStats: ProcessingStatsRepository,
    private val translator: SummaryTranslator,
    categoryRepository: CategoryRepository,
    searchHighlights: SearchHighlights,
    private val appSettings: AppSettingsRepository,
    private val noteRepository: NoteRepository,
    private val collectionRepository: CollectionRepository,
    private val exportRepository: ExportRepository,
    private val linkCheckRepository: LinkCheckRepository
) : ViewModel() {

    // ルート "bookmark/{bookmarkId}" の引数。2 画面表示の詳細ペインでは、ペインを開くときに同じキーで渡す
    private val bookmarkId: Long = checkNotNull(savedStateHandle.get<Long>(ARG_BOOKMARK_ID))

    private val bookmarkFlow = repository.observeBookmark(bookmarkId)

    private val messageChannel = Channel<BookmarkDetailMessage>(Channel.BUFFERED)
    val messages: Flow<BookmarkDetailMessage> = messageChannel.receiveAsFlow()

    /** AI 処理の状態が変わるたび(=埋め込みが更新され得るとき)に関連ブックマークを取り直す */
    private val relatedFlow = bookmarkFlow
        .map { it?.aiStatus }
        .distinctUntilChanged()
        .mapLatest {
            try {
                repository.getRelatedBookmarks(bookmarkId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 関連は補助的な表示なので、取れなくても詳細は出す
                Timber.w(e, "関連ブックマークを取得できませんでした")
                emptyList()
            }
        }
        .onStart { emit(emptyList()) }

    private val tagNamesFlow = repository.observeAllTags()
        .map { tags -> tags.map { it.name } }
        .catch { e ->
            if (e is CancellationException) throw e
            emit(emptyList())
        }
        .onStart { emit(emptyList()) }

    /** 処理の進み具合と残り時間の目安。補助的な表示なので、取れなくても詳細は出す */
    private val stageProgressFlow = repository.observeStageProgress(bookmarkId)
        .catch { emit(null) }
        .onStart { emit(null) }
    private val stageAveragesFlow = processingStats.averageStageDurations
        .catch { emit(emptyMap()) }
        .onStart { emit(emptyMap()) }

    /** 要約の履歴。補助的な表示なので、取れなくても詳細は出す */
    private val summaryHistoryFlow = repository.observeSummaryHistory(bookmarkId)
        .catch { e ->
            if (e is CancellationException) throw e
            emit(emptyList())
        }
        .onStart { emit(emptyList()) }

    /** 選べるカテゴリ。補助的な表示なので、取れなくても詳細は出す */
    private val categoryChoicesFlow = categoryRepository.observeCategories()
        .catch { e ->
            if (e is CancellationException) throw e
            emit(emptyList())
        }
        .onStart { emit(emptyList()) }

    private val highlightTermsFlow = searchHighlights.termsFor(bookmarkId)

    /** 読書モードの見た目。補助的な設定なので、読めなければ既定の見た目にする */
    private val readingStyleFlow = appSettings.readingStyle
        .catch { e ->
            if (e is CancellationException) throw e
            emit(ReadingStyle())
        }

    /** 自分のノート。補助的な表示なので、読めなければ空にする */
    private val notesFlow = noteRepository.observeNotes(bookmarkId)
        .catch { e ->
            if (e is CancellationException) throw e
            Timber.w(e, "ノートの読み込みに失敗")
            emit(emptyList())
        }

    /** 作ってあるコレクション。補助的な表示なので、読めなければ空にする */
    private val collectionsFlow = collectionRepository.observeCollections()
        .catch { e ->
            if (e is CancellationException) throw e
            Timber.w(e, "コレクションの読み込みに失敗")
            emit(emptyList())
        }

    private val collectionIdsFlow = collectionRepository.observeCollectionIdsOf(bookmarkId)
        .catch { e ->
            if (e is CancellationException) throw e
            Timber.w(e, "コレクションへの所属の読み込みに失敗")
            emit(emptySet())
        }

    /** コレクションへの所属とリンク切れの確認の状態(補助的な表示。[Extras] にまとめる) */
    private class Membership(
        val collections: List<BookmarkCollection>,
        val collectionIds: Set<Long>,
        val linkState: LinkCheckState?,
        val linkChecking: Boolean
    )

    /** リンクを今すぐ確認している間 true */
    private val linkChecking = MutableStateFlow(false)

    /** リンク切れの確認の状態。補助的な表示なので、読めなければ無いものとして扱う */
    private val linkStateFlow = linkCheckRepository.observeState(bookmarkId)
        .catch { e ->
            if (e is CancellationException) throw e
            Timber.w(e, "リンクの確認の状態の読み込みに失敗")
            emit(null)
        }

    private val membershipFlow = combine(collectionsFlow, collectionIdsFlow, linkStateFlow, linkChecking, ::Membership)

    /** [uiState] に合わせる補助的な状態(5つ以内の Flow にまとめるための入れ物) */
    private class Extras(
        val terms: List<String>,
        val readingStyle: ReadingStyle,
        val notes: List<BookmarkNote>,
        val membership: Membership
    )

    private val translation = MutableStateFlow<TranslationState>(TranslationState.Idle)
    private var translationJob: Job? = null

    private val baseState =
        combine(
            bookmarkFlow, relatedFlow, tagNamesFlow, stageProgressFlow, stageAveragesFlow
        ) { bookmark, related, tagNames, stageProgress, stageAverages ->
            BookmarkDetailUiState(
                isLoading = false, bookmark = bookmark, related = related, allTagNames = tagNames,
                stageProgress = stageProgress, stageAverages = stageAverages
            )
        }

    val uiState: StateFlow<BookmarkDetailUiState> =
        combine(
            baseState, summaryHistoryFlow, translation, categoryChoicesFlow,
            combine(highlightTermsFlow, readingStyleFlow, notesFlow, membershipFlow, ::Extras)
        ) { state, history, translation, categories, extras ->
            state.copy(
                summaryHistory = history, translation = translation, categoryChoices = categories,
                highlightTerms = extras.terms, readingStyle = extras.readingStyle, notes = extras.notes,
                collections = extras.membership.collections, collectionIds = extras.membership.collectionIds,
                linkState = extras.membership.linkState, linkChecking = extras.membership.linkChecking
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookmarkDetailUiState())

    init {
        // 開いたことを記録する(再発見の判定などに使う)
        viewModelScope.launch { repository.markAccessed(bookmarkId) }
    }

    /** カテゴリを選ぶ(自分で選んだものとして、AI の再処理では置き換わらない) */
    fun setCategory(name: String) {
        viewModelScope.launch { repository.setCategory(bookmarkId, name) }
    }

    /** カテゴリを AI に任せる状態に戻し、タグとカテゴリを付け直してもらう */
    fun resetCategory() {
        viewModelScope.launch { repository.resetCategory(bookmarkId) }
    }

    fun addTag(name: String) {
        val trimmed = name.trim().removePrefix("#").trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.addTag(bookmarkId, trimmed) }
    }

    /** タグを外す。Snackbar の「元に戻す」で [addTag] し直せるよう知らせる */
    fun removeTag(name: String) {
        viewModelScope.launch {
            repository.removeTag(bookmarkId, name)
            messageChannel.send(BookmarkDetailMessage.TagRemoved(name))
        }
    }

    /** ノート(注釈)を足す。[quote] は引用した文章。空白だけのノートは足さない */
    fun addNote(text: String, quote: String?) {
        viewModelScope.launch {
            if (noteRepository.addNote(bookmarkId, text, quote) == null && text.isNotBlank()) {
                messageChannel.send(BookmarkDetailMessage.Failed("ノートを保存できませんでした。"))
            }
        }
    }

    fun updateNote(id: Long, text: String, quote: String?) {
        viewModelScope.launch {
            if (!noteRepository.updateNote(id, text, quote)) {
                messageChannel.send(BookmarkDetailMessage.Failed("ノートを保存できませんでした。"))
            }
        }
    }

    /** ノートを削除する。Snackbar の「元に戻す」で [restoreNote] できるよう知らせる */
    fun deleteNote(note: BookmarkNote) {
        viewModelScope.launch {
            noteRepository.deleteNote(note.id)
            messageChannel.send(BookmarkDetailMessage.NoteDeleted(note))
        }
    }

    fun restoreNote(note: BookmarkNote) {
        viewModelScope.launch { noteRepository.restoreNote(note) }
    }

    /** このブックマークをコレクションに入れる・外す。名前は Snackbar で知らせるためのもの */
    fun setInCollection(collection: BookmarkCollection, inCollection: Boolean) {
        viewModelScope.launch {
            if (inCollection) collectionRepository.add(collection.id, bookmarkId) else collectionRepository.remove(collection.id, bookmarkId)
            messageChannel.send(BookmarkDetailMessage.CollectionChanged(collection.name, inCollection))
        }
    }

    /** 新しいコレクションを作り、このブックマークを入れる。結果は [onResult] に返す(重複・不正な名前は、シートに理由を出す) */
    fun createCollectionAndAdd(name: String, onResult: (CollectionResult) -> Unit) {
        viewModelScope.launch {
            val result = collectionRepository.create(name)
            if (result is CollectionResult.Saved) {
                collectionRepository.add(result.id, bookmarkId)
                messageChannel.send(BookmarkDetailMessage.CollectionChanged(CollectionNames.normalize(name).orEmpty(), true))
            }
            onResult(result)
        }
    }

    /** このブックマークを [format] で [uri] に書き出す(ノートも入る) */
    fun exportBookmark(format: ExportFormat, uri: String) {
        viewModelScope.launch {
            try {
                messageChannel.send(BookmarkDetailMessage.Exported(exportRepository.exportBookmark(bookmarkId, format, uri)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExportException) {
                messageChannel.send(BookmarkDetailMessage.Failed(e.message ?: "書き出せませんでした。"))
            } catch (e: Exception) {
                Timber.w(e, "ブックマークの書き出しに失敗")
                messageChannel.send(BookmarkDetailMessage.Failed("書き出せませんでした。"))
            }
        }
    }

    /** リンクが今も開けるかを今すぐ確認する(「もう一度確認」)。結果は Snackbar で知らせる */
    fun recheckLink() {
        if (linkChecking.value) return
        viewModelScope.launch {
            linkChecking.value = true
            try {
                messageChannel.send(BookmarkDetailMessage.LinkChecked(linkCheckRepository.checkNow(bookmarkId)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "リンクの確認に失敗")
                messageChannel.send(BookmarkDetailMessage.LinkChecked(null))
            } finally {
                linkChecking.value = false
            }
        }
    }

    /** このリンクの夜の確認をやめる(リンク切れの知らせも出さない) */
    fun muteLinkCheck() {
        viewModelScope.launch { linkCheckRepository.setMuted(bookmarkId, true) }
    }

    fun toggleFavorite() {
        val bookmark = uiState.value.bookmark ?: return
        viewModelScope.launch { repository.setFavorite(bookmarkId, !bookmark.isFavorite) }
    }

    /** 読書モードの文字の大きさ・背景・書体を変えて保存する(#59) */
    fun setReadingStyle(style: ReadingStyle) {
        viewModelScope.launch {
            try {
                appSettings.setReadingStyle(style)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "読書モードの見た目を保存できませんでした")
            }
        }
    }

    /** 読んだことにする / 未読に戻す(#60)。開いたときは自動で既読になるので、未読に戻すのに使う */
    fun toggleRead() {
        val bookmark = uiState.value.bookmark ?: return
        viewModelScope.launch { repository.setRead(bookmarkId, !bookmark.isRead) }
    }

    /** アーカイブする/戻す。取り消しは同じ関数を逆の値で呼ぶ */
    fun setArchived(archived: Boolean, notify: Boolean = true) {
        viewModelScope.launch {
            repository.setArchived(bookmarkId, archived)
            if (notify) messageChannel.send(BookmarkDetailMessage.ArchiveChanged(archived))
        }
    }

    /** タイトル・本文(メモ)を保存する。タイトルが空なら保存しない */
    fun saveEdit(title: String, content: String) {
        val newTitle = title.trim()
        if (newTitle.isEmpty()) return
        viewModelScope.launch {
            repository.updateTitleAndContent(bookmarkId, newTitle, content.trim().ifEmpty { null })
            messageChannel.send(BookmarkDetailMessage.Edited)
        }
    }

    /** 要約・タグ・カテゴリ・埋め込みの AI 処理をやり直す */
    /** AI 処理をやり直す。[scope] で要約だけ・タグだけを作り直せる(既定は全部) */
    fun reprocess(scope: AnalysisScope = AnalysisScope.ALL) {
        viewModelScope.launch {
            repository.reprocess(bookmarkId, scope)
            messageChannel.send(BookmarkDetailMessage.ReprocessStarted)
        }
    }

    /** 要約を手で直す。直した要約は、再処理しても置き換わらない(#19) */
    fun editSummary(summary: String) {
        if (summary.isBlank()) return
        viewModelScope.launch {
            repository.editSummary(bookmarkId, summary)
            messageChannel.send(BookmarkDetailMessage.SummaryEdited)
        }
    }

    /** 履歴の版に要約を戻す(#20) */
    fun restoreSummary(versionId: Long) {
        viewModelScope.launch {
            repository.restoreSummary(bookmarkId, versionId)
            messageChannel.send(BookmarkDetailMessage.SummaryRestored)
        }
    }

    /** 今の要約を [target] の言語に翻訳する(#24)。前の翻訳が終わっていなければやめる。 */
    fun translateSummary(target: SummaryLanguage) {
        val summary = uiState.value.bookmark?.summary?.takeIf { it.isNotBlank() } ?: return
        translationJob?.cancel()
        translation.value = TranslationState.Loading(target)
        translationJob = viewModelScope.launch {
            translation.value = try {
                TranslationState.Done(target, translator.translate(summary, target))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "要約を翻訳できませんでした")
                TranslationState.Failed(target, e.toLlmUserMessage("翻訳できませんでした"))
            }
        }
    }

    /** 翻訳を閉じる */
    fun clearTranslation() {
        translationJob?.cancel()
        translation.value = TranslationState.Idle
    }

    /**
     * タイトル・本文を直した後の「AIで要約し直す」。手で直した要約は、ふつうの再処理では置き換わらないため、
     * このときは要約を作り直す(直した要約は履歴に残る)。
     */
    fun reprocessAfterEdit() {
        val edited = uiState.value.bookmark?.summaryEdited == true
        reprocess(if (edited) AnalysisScope.SUMMARY else AnalysisScope.ALL)
    }

    /** 削除して、完了後に onDeleted を呼ぶ(画面を閉じる用)。戻った一覧で「元に戻す」を出せるよう知らせる */
    fun delete(onDeleted: () -> Unit) {
        val bookmark = uiState.value.bookmark ?: return
        viewModelScope.launch {
            try {
                repository.deleteBookmark(bookmark)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "ブックマークの削除に失敗")
                messageChannel.send(BookmarkDetailMessage.Failed("削除できませんでした。もう一度お試しください。"))
                return@launch
            }
            recentlyDeleted.notifyDeleted(bookmark)
            onDeleted()
        }
    }

    companion object {
        /** ナビゲーション引数(と SavedStateHandle)のキー。 */
        const val ARG_BOOKMARK_ID = "bookmarkId"
    }
}
