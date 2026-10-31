package com.unchunks.echomark.ui.snapshot

import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.snapshot.PageSnapshotRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/** オフラインコピーを開く画面の状態 */
internal sealed interface SnapshotViewerState {
    data object Loading : SnapshotViewerState

    /** オフラインコピーが無い(消された・ファイルが失われた) */
    data object Missing : SnapshotViewerState

    /**
     * 開ける。
     * @property fileUrl WebView で開く MHTML の file:// の URL
     * @property originalUrl 元のページの URL(ブラウザで開くボタン用)
     */
    data class Ready(
        val bookmarkId: Long,
        val title: String,
        val originalUrl: String?,
        val fileUrl: String,
        val pdfPath: String?,
        val savedAt: Long
    ) : SnapshotViewerState {
        val hasPdf: Boolean get() = pdfPath != null
    }
}

/** [SnapshotViewerActivity] が開くブックマークの ID を受け取る extra のキー */
internal const val EXTRA_SNAPSHOT_BOOKMARK_ID = "com.unchunks.echomark.extra.SNAPSHOT_BOOKMARK_ID"

@HiltViewModel
internal class SnapshotViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bookmarks: BookmarkRepository,
    private val snapshots: PageSnapshotRepository
) : ViewModel() {

    private val bookmarkId: Long = savedStateHandle.get<Long>(EXTRA_SNAPSHOT_BOOKMARK_ID) ?: -1L

    private val stateFlow = MutableStateFlow<SnapshotViewerState>(SnapshotViewerState.Loading)
    val state: StateFlow<SnapshotViewerState> = stateFlow.asStateFlow()

    init {
        viewModelScope.launch {
            stateFlow.value = try {
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "オフラインコピーを読み込めなかった")
                SnapshotViewerState.Missing
            }
        }
    }

    private suspend fun load(): SnapshotViewerState {
        val bookmark = bookmarks.getBookmarkById(bookmarkId) ?: return SnapshotViewerState.Missing
        val file: File = snapshots.fileOf(bookmark.snapshotPath) ?: return SnapshotViewerState.Missing
        return SnapshotViewerState.Ready(
            bookmarkId = bookmark.id,
            title = bookmark.title,
            originalUrl = bookmark.contentUri,
            fileUrl = file.toUri().toString(),
            pdfPath = bookmark.snapshotPdfPath?.takeIf { snapshots.fileOf(it) != null },
            savedAt = bookmark.snapshotAt ?: file.lastModified()
        )
    }
}
