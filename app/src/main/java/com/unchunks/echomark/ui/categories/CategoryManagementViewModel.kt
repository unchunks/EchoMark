package com.unchunks.echomark.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.model.CategoryEntry
import com.unchunks.echomark.domain.repository.CategoryRepository
import com.unchunks.echomark.domain.repository.CategoryResult
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

data class CategoryManagementUiState(
    val isLoading: Boolean = true,
    val entries: List<CategoryEntry> = emptyList(),
    val errorMessage: String? = null
)

/** カテゴリの画面で一度だけ伝える出来事(Snackbar で知らせる)。 */
sealed interface CategoryManagementMessage {
    data class Added(val name: String) : CategoryManagementMessage
    data class Updated(val name: String) : CategoryManagementMessage
    data class Deleted(val name: String) : CategoryManagementMessage
    data class Failed(val message: String) : CategoryManagementMessage
}

@HiltViewModel
class CategoryManagementViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val messageChannel = Channel<CategoryManagementMessage>(Channel.BUFFERED)
    val messages: Flow<CategoryManagementMessage> = messageChannel.receiveAsFlow()

    val uiState: StateFlow<CategoryManagementUiState> = categoryRepository.observeCategories()
        .map { CategoryManagementUiState(isLoading = false, entries = it) }
        .catch { e ->
            if (e is CancellationException) throw e
            Timber.w(e, "カテゴリ一覧の読み込みに失敗")
            emit(CategoryManagementUiState(isLoading = false, errorMessage = "カテゴリを読み込めませんでした。"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoryManagementUiState())

    /** カテゴリを作る。名前の重複などは画面でも確かめているが、ここでも確かめた結果を知らせる */
    fun add(name: String, description: String) {
        send(failure = "カテゴリを作れませんでした") {
            categoryRepository.addCategory(name, description).toMessage(created = true)
        }
    }

    fun update(entry: CategoryEntry, name: String, description: String) {
        val id = entry.id ?: return
        send(failure = "カテゴリを変更できませんでした") {
            categoryRepository.updateCategory(id, name, description).toMessage(created = false)
        }
    }

    fun delete(entry: CategoryEntry) {
        val id = entry.id ?: return
        send(failure = "カテゴリを削除できませんでした") {
            categoryRepository.deleteCategory(id)
            CategoryManagementMessage.Deleted(entry.name)
        }
    }

    private fun send(failure: String, block: suspend () -> CategoryManagementMessage) {
        viewModelScope.launch {
            val message = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, failure)
                CategoryManagementMessage.Failed(failure)
            }
            messageChannel.send(message)
        }
    }

    private fun CategoryResult.toMessage(created: Boolean): CategoryManagementMessage = when (this) {
        is CategoryResult.Saved -> if (created) CategoryManagementMessage.Added(name) else CategoryManagementMessage.Updated(name)
        is CategoryResult.Duplicate -> CategoryManagementMessage.Failed("「${existing}」と同じ名前のカテゴリがあります")
        is CategoryResult.Invalid -> CategoryManagementMessage.Failed(message)
    }
}
