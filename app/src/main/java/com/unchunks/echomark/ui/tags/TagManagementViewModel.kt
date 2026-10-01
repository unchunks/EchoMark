package com.unchunks.echomark.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.model.TagWithCount
import com.unchunks.echomark.domain.repository.TagRenameResult
import com.unchunks.echomark.domain.repository.TagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class TagManagementUiState(
    val isLoading: Boolean = true,
    val tags: List<TagWithCount> = emptyList(),
    val errorMessage: String? = null
)

/** タグ管理画面で一度だけ伝える出来事(Snackbar で知らせる)。 */
sealed interface TagManagementMessage {
    data class Renamed(val newName: String) : TagManagementMessage
    data class Merged(val fromName: String, val intoName: String) : TagManagementMessage
    data class Deleted(val name: String) : TagManagementMessage
    data class Failed(val message: String) : TagManagementMessage
}

@HiltViewModel
class TagManagementViewModel @Inject constructor(
    private val tagRepository: TagRepository
) : ViewModel() {

    private val messageChannel = Channel<TagManagementMessage>(Channel.BUFFERED)
    val messages: Flow<TagManagementMessage> = messageChannel.receiveAsFlow()

    val uiState: StateFlow<TagManagementUiState> = tagRepository.observeTagsWithCount()
        .map { TagManagementUiState(isLoading = false, tags = it) }
        .catch { e ->
            if (e is CancellationException) throw e
            Timber.w(e, "タグ一覧の読み込みに失敗")
            emit(TagManagementUiState(isLoading = false, errorMessage = "タグを読み込めませんでした。"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TagManagementUiState())

    /** 名前を変更する。同名のタグが既にあれば統合される */
    fun rename(tag: TagWithCount, newName: String) {
        viewModelScope.launch {
            val message = try {
                when (val result = tagRepository.renameTag(tag.id, newName)) {
                    is TagRenameResult.Renamed -> TagManagementMessage.Renamed(newName.trim())
                    is TagRenameResult.Merged -> TagManagementMessage.Merged(tag.name, newName.trim())
                    TagRenameResult.Unchanged -> null
                    TagRenameResult.Invalid -> TagManagementMessage.Failed("タグ名を入力してください")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "タグ名の変更に失敗")
                TagManagementMessage.Failed("タグ名を変更できませんでした")
            }
            message?.let { messageChannel.send(it) }
        }
    }

    fun delete(tag: TagWithCount) {
        viewModelScope.launch {
            val message = try {
                tagRepository.deleteTag(tag.id)
                TagManagementMessage.Deleted(tag.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "タグの削除に失敗")
                TagManagementMessage.Failed("タグを削除できませんでした")
            }
            messageChannel.send(message)
        }
    }
}
