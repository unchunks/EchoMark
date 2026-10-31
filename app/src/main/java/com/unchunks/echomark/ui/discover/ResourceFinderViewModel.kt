package com.unchunks.echomark.ui.discover

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.data.ai.discover.ResourceCandidate
import com.unchunks.echomark.data.ai.discover.ResourceFinder
import com.unchunks.echomark.domain.repository.AttachmentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/** 「資料を探す」画面の状態。 */
data class ResourceFinderUiState(
    val topic: String = "",
    val listUrl: String = "",
    /** 端末内で選んだファイル(探す対象) */
    val files: List<ResourceCandidate> = emptyList(),
    val isRunning: Boolean = false,
    /** 直近の探索で保存できた資料。まだ探していなければ null */
    val saved: List<ResourceCandidate>? = null,
    /** 入力の不足や失敗の説明(なければ null) */
    val message: String? = null
)

@HiltViewModel
class ResourceFinderViewModel @Inject constructor(
    private val finder: ResourceFinder,
    private val attachments: AttachmentRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResourceFinderUiState())
    val uiState: StateFlow<ResourceFinderUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    fun onTopicChange(text: String) = _uiState.update { it.copy(topic = text) }

    fun onListUrlChange(text: String) = _uiState.update { it.copy(listUrl = text) }

    /** 端末内で選んだファイルを探す対象に加える(同じ URI は1回だけ)。名前が分からなければ URI の末尾を使う。 */
    fun addFiles(uris: List<Uri>) {
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.map { it.toString() }.map { uri ->
                    ResourceCandidate.file(uri, attachments.displayNameOf(uri) ?: uri.substringAfterLast('/'))
                }
            }
            _uiState.update { state ->
                val known = state.files.map { it.ref }.toSet()
                state.copy(files = state.files + added.filter { it.ref !in known }, message = null)
            }
        }
    }

    fun removeFile(ref: String) = _uiState.update { state ->
        state.copy(files = state.files.filterNot { it.ref == ref })
    }

    /** Web の一覧ページのリンクから探す。 */
    fun searchWeb() {
        val state = _uiState.value
        val listUrl = state.listUrl.trim()
        when {
            state.topic.isBlank() -> return showMessage(MESSAGE_NO_TOPIC)
            listUrl.isEmpty() -> return showMessage(MESSAGE_NO_URL)
            !listUrl.startsWith("http://") && !listUrl.startsWith("https://") -> return showMessage(MESSAGE_BAD_URL)
        }
        search {
            try {
                finder.findOnWeb(state.topic, listUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "一覧ページを取得できなかった")
                throw SearchFailure("一覧ページを取得できませんでした。URL を確かめてください")
            }
        }
    }

    /** 端末内で選んだファイルから探す。 */
    fun searchFiles() {
        val state = _uiState.value
        when {
            state.topic.isBlank() -> return showMessage(MESSAGE_NO_TOPIC)
            state.files.isEmpty() -> return showMessage(MESSAGE_NO_FILES)
        }
        search { finder.findInFiles(state.topic, state.files) }
    }

    private fun search(block: suspend () -> List<ResourceCandidate>) {
        if (searchJob?.isActive == true) return
        _uiState.update { it.copy(isRunning = true, saved = null, message = null) }
        searchJob = viewModelScope.launch {
            try {
                val saved = block()
                _uiState.update { it.copy(isRunning = false, saved = saved) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SearchFailure) {
                _uiState.update { it.copy(isRunning = false, message = e.message) }
            } catch (e: Exception) {
                Timber.w(e, "資料の探索に失敗")
                _uiState.update { it.copy(isRunning = false, message = "探せませんでした。端末内の AI の準備を確かめてください") }
            }
        }
    }

    private fun showMessage(text: String) = _uiState.update { it.copy(message = text) }

    private class SearchFailure(message: String) : Exception(message)

    companion object {
        const val MESSAGE_NO_TOPIC = "調べたいことを入力してください"
        const val MESSAGE_NO_URL = "一覧ページの URL を入力してください"
        const val MESSAGE_BAD_URL = "http:// か https:// から始まる URL を入力してください"
        const val MESSAGE_NO_FILES = "探すファイルを選んでください"
    }
}
