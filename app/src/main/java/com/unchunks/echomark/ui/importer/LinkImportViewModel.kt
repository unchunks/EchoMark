package com.unchunks.echomark.ui.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.importer.ImportParseResult
import com.unchunks.echomark.domain.importer.ImportResult
import com.unchunks.echomark.domain.importer.LinkImportException
import com.unchunks.echomark.domain.importer.LinkImportSource
import com.unchunks.echomark.domain.repository.BookmarkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** 他のサービスから取り込む操作の状態 */
sealed interface LinkImportState {
    data object Idle : LinkImportState

    /** 選んだファイルを読んでいる */
    data object Reading : LinkImportState

    /** 読み取れた。取り込む前の確認。[folderAsTag] は「フォルダ名をタグにする」 */
    data class Preview(val parsed: ImportParseResult, val folderAsTag: Boolean) : LinkImportState

    /** 保存している(閉じられない) */
    data class Importing(val done: Int, val total: Int) : LinkImportState

    data class Done(val result: ImportResult) : LinkImportState

    data class Failed(val message: String) : LinkImportState
}

/**
 * 他のサービス(Pocket・Raindrop.io・Instapaper・Pinboard・ブラウザなど)の書き出しファイルを取り込む。
 * ファイルを選ぶ → 読み取って件数などを見せる → 取り込む、の順。ログインやアカウントは使わず、ファイルだけを読む。
 */
@HiltViewModel
class LinkImportViewModel @Inject constructor(
    private val source: LinkImportSource,
    private val repository: BookmarkRepository
) : ViewModel() {

    private val stateFlow = MutableStateFlow<LinkImportState>(LinkImportState.Idle)
    val state: StateFlow<LinkImportState> = stateFlow.asStateFlow()

    /** 選んだファイルを読む */
    fun onFilePicked(uri: String) {
        if (stateFlow.value is LinkImportState.Reading || stateFlow.value is LinkImportState.Importing) return
        stateFlow.value = LinkImportState.Reading
        viewModelScope.launch {
            stateFlow.value = try {
                val parsed = source.read(uri)
                if (parsed.links.isEmpty()) {
                    LinkImportState.Failed("取り込めるリンクが見つかりませんでした")
                } else {
                    // フォルダがあるときは、元の整理を残せるよう、最初からタグにする
                    LinkImportState.Preview(parsed, folderAsTag = parsed.hasFolders)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LinkImportException) {
                LinkImportState.Failed(e.message ?: "ファイルを読み込めませんでした")
            } catch (e: Exception) {
                Timber.w(e, "取り込むファイルを読めなかった")
                LinkImportState.Failed("ファイルを読み込めませんでした")
            }
        }
    }

    fun setFolderAsTag(enabled: Boolean) {
        val preview = stateFlow.value as? LinkImportState.Preview ?: return
        stateFlow.value = preview.copy(folderAsTag = enabled)
    }

    /** 確認した内容で取り込む */
    fun startImport() {
        val preview = stateFlow.value as? LinkImportState.Preview ?: return
        val links = preview.parsed.links
        stateFlow.value = LinkImportState.Importing(0, links.size)
        viewModelScope.launch {
            stateFlow.value = try {
                val result = repository.importLinks(links, preview.folderAsTag) { done, total ->
                    stateFlow.value = LinkImportState.Importing(done, total)
                }
                LinkImportState.Done(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "取り込みに失敗")
                LinkImportState.Failed("取り込めませんでした。もう一度お試しください")
            }
        }
    }

    /** 画面を閉じる(保存している最中は閉じない) */
    fun dismiss() {
        if (stateFlow.value !is LinkImportState.Importing) stateFlow.value = LinkImportState.Idle
    }
}
