package com.unchunks.echomark.ui.snapshot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.snapshot.PageSnapshotRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/** ページのオフラインコピーを作る操作の状態 */
sealed interface SnapshotOperation {
    data object Idle : SnapshotOperation

    /** [url] のページを読み込んで、コピーを作っている */
    data class Capturing(val bookmarkId: Long, val url: String) : SnapshotOperation

    /** できたコピーをアプリ内に保存している */
    data object Saving : SnapshotOperation
}

/** 操作の結果(Snackbar で知らせる) */
sealed interface SnapshotMessage {
    /** 保存した。[hasPdf] が false なら PDF は作れなかった */
    data class Saved(val hasPdf: Boolean) : SnapshotMessage

    data object Removed : SnapshotMessage

    data class Failed(val message: String) : SnapshotMessage
}

/** 詳細画面のオフラインコピー(保存・削除)。ページの読み込みと書き出しは画面の WebView([PageSnapshotter])が行い、ここは保存の記録を扱う。 */
@HiltViewModel
class PageSnapshotViewModel @Inject constructor(
    private val repository: PageSnapshotRepository
) : ViewModel() {

    private val operationFlow = MutableStateFlow<SnapshotOperation>(SnapshotOperation.Idle)
    val operation: StateFlow<SnapshotOperation> = operationFlow.asStateFlow()

    private val messageChannel = Channel<SnapshotMessage>(Channel.BUFFERED)
    val messages: Flow<SnapshotMessage> = messageChannel.receiveAsFlow()

    /** [bookmark] のページを読み込んでコピーを作り始める(http / https のリンクだけ。作っている最中は何もしない) */
    fun start(bookmark: Bookmark) {
        val url = bookmark.contentUri
        if (url == null || !canSnapshot(url) || operationFlow.value != SnapshotOperation.Idle) return
        operationFlow.value = SnapshotOperation.Capturing(bookmark.id, url)
    }

    /** 画面の WebView がコピーを作れた。[mhtml] と [pdf](作れなければ null)の一時ファイルをアプリ内に保存する */
    fun onCaptured(mhtml: File, pdf: File?) {
        val capturing = operationFlow.value as? SnapshotOperation.Capturing ?: run {
            mhtml.delete()
            pdf?.delete()
            return
        }
        operationFlow.value = SnapshotOperation.Saving
        viewModelScope.launch {
            try {
                repository.save(capturing.bookmarkId, mhtml, pdf)
                messageChannel.send(SnapshotMessage.Saved(hasPdf = pdf != null))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "オフラインコピーを保存できなかった")
                messageChannel.send(SnapshotMessage.Failed("オフラインコピーを保存できませんでした。空き容量を確かめてください"))
            } finally {
                // 保存できなかったときの一時ファイルを残さない(保存できたときは移した後なので、消すものは無い)
                mhtml.delete()
                pdf?.delete()
                operationFlow.value = SnapshotOperation.Idle
            }
        }
    }

    /** コピーを作れなかった(読み込めない・保存できない)。理由を知らせる */
    fun onFailed(message: String) {
        operationFlow.value = SnapshotOperation.Idle
        messageChannel.trySend(SnapshotMessage.Failed(message))
    }

    /** 利用者が作るのをやめた */
    fun cancel() {
        if (operationFlow.value is SnapshotOperation.Capturing) operationFlow.value = SnapshotOperation.Idle
    }

    /** ブックマークのコピーを消す */
    fun remove(bookmarkId: Long) {
        viewModelScope.launch {
            try {
                repository.remove(bookmarkId)
                messageChannel.send(SnapshotMessage.Removed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "オフラインコピーを消せなかった")
                messageChannel.send(SnapshotMessage.Failed("オフラインコピーを消せませんでした"))
            }
        }
    }
}
