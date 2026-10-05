package com.unchunks.echomark.ui.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.bookmark.model.AttachmentException
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.repository.AttachmentRepository
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.ui.common.SelectedFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** 共有シートの保存状態 */
sealed interface ShareSaveStatus {
    /** 入力中 */
    data object Editing : ShareSaveStatus

    /** 保存中。ファイルを保存しているときは [done] / [total] 件目まで終わった */
    data class Saving(val done: Int = 0, val total: Int = 0) : ShareSaveStatus

    /**
     * 保存した。[isDuplicate] なら同じ URL が保存済みだった。
     * ファイルは [savedCount] 件を保存し、[failedCount] 件は保存できなかった
     */
    data class Saved(val isDuplicate: Boolean, val savedCount: Int = 1, val failedCount: Int = 0) : ShareSaveStatus

    /** 保存できなかった(入力に戻って再試行できる) */
    data class Failed(val message: String) : ShareSaveStatus
}

/** 他アプリの共有メニューから受け取った内容を保存する(共有シート用)。 */
@HiltViewModel
class ShareViewModel @Inject constructor(
    private val repository: BookmarkRepository,
    private val attachmentRepository: AttachmentRepository
) : ViewModel() {

    private val _status = MutableStateFlow<ShareSaveStatus>(ShareSaveStatus.Editing)
    val status: StateFlow<ShareSaveStatus> = _status.asStateFlow()

    /**
     * 保存する。ファイルがあればファイルごとに1件ずつ、URL があればリンク(同じ URL は重複として既存を使う)、
     * 無ければテキストのメモとして保存し、入力されたタグを付ける。
     */
    fun save(shared: SharedContent, title: String, memo: String, tagsInput: String) {
        if (_status.value is ShareSaveStatus.Saving || _status.value is ShareSaveStatus.Saved) return
        if (shared.files.isNotEmpty()) {
            saveFiles(shared.files, title, memo, tagsInput)
            return
        }
        val url = shared.url
        if (url == null && title.isBlank() && shared.text.isBlank()) return
        _status.value = ShareSaveStatus.Saving()
        viewModelScope.launch {
            _status.value = try {
                val result = if (url != null) {
                    repository.saveUrlBookmark(url, title.takeIf { it.isNotBlank() }, memo)
                } else {
                    // URL がなければテキストとして保存(本文 = 共有テキスト、メモがあれば追記)
                    val now = System.currentTimeMillis()
                    val content = if (memo.isBlank()) shared.text else shared.text + "\n\n" + memo.trim()
                    repository.saveBookmarkWithResult(
                        Bookmark(
                            type = BookmarkType.TEXT,
                            content = content,
                            title = title.trim().ifEmpty { shared.tentativeTitle },
                            createdAt = now,
                            lastAccessedAt = now
                        )
                    )
                }
                parseTags(tagsInput).forEach { repository.addTag(result.id, it) }
                ShareSaveStatus.Saved(result.isDuplicate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "共有からの保存に失敗")
                ShareSaveStatus.Failed("保存できませんでした。もう一度お試しください。")
            }
        }
    }

    /**
     * ファイルを1件ずつアプリ内へコピーしてブックマークにする。タイトルは1件のときだけ使い(空ならファイル名)、
     * メモとタグはすべてに付ける。保存できなかったものがあっても残りは続ける。
     */
    private fun saveFiles(files: List<SelectedFile>, title: String, memo: String, tagsInput: String) {
        val tags = parseTags(tagsInput)
        _status.value = ShareSaveStatus.Saving(0, files.size)
        viewModelScope.launch {
            var saved = 0
            var firstError: String? = null
            files.forEachIndexed { index, file ->
                try {
                    val stored = attachmentRepository.importFile(file.uri)
                    val result = repository.saveFileBookmark(
                        stored,
                        title = title.takeIf { files.size == 1 && it.isNotBlank() },
                        memo = memo.takeIf { it.isNotBlank() }
                    )
                    tags.forEach { repository.addTag(result.id, it) }
                    saved++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AttachmentException) {
                    firstError = firstError ?: e.error.userMessage
                } catch (e: Exception) {
                    Timber.w(e, "共有されたファイルの保存に失敗")
                    firstError = firstError ?: "保存できませんでした。もう一度お試しください。"
                }
                _status.value = ShareSaveStatus.Saving(index + 1, files.size)
            }
            _status.value = if (saved == 0) {
                ShareSaveStatus.Failed(firstError ?: "保存できませんでした。もう一度お試しください。")
            } else {
                ShareSaveStatus.Saved(isDuplicate = false, savedCount = saved, failedCount = files.size - saved)
            }
        }
    }

    companion object {
        /** 「#Android, Kotlin 読書」のような入力をタグ名に分ける(カンマ・読点・空白区切り、先頭の # は除く) */
        internal fun parseTags(input: String): List<String> =
            input.split(',', '、', ' ', '　', '\n')
                .map { it.trim().removePrefix("#").trim() }
                .filter { it.isNotEmpty() }
                .distinct()
    }
}
