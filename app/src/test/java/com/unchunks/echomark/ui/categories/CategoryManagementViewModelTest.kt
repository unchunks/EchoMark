package com.unchunks.echomark.ui.categories

import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.echomark.domain.model.CategoryEntry
import com.unchunks.echomark.domain.model.CategoryKind
import com.unchunks.echomark.domain.repository.CategoryResult
import com.unchunks.echomark.testing.FakeCategoryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CategoryManagementViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repository = FakeCategoryRepository()
    private val builtIn = CategoryEntry("技術", "技術の話題", CategoryKind.BUILT_IN, 3)
    private val mine = CategoryEntry("旅行", "行き先や宿", CategoryKind.USER, 2, id = 1)

    private fun TestScope.start(viewModel: CategoryManagementViewModel): MutableList<CategoryManagementMessage> {
        val messages = mutableListOf<CategoryManagementMessage>()
        backgroundScope.launch { viewModel.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.messages.toList(messages) }
        return messages
    }

    @Test
    fun 一覧が出る() = runTest {
        repository.entries.value = listOf(mine, builtIn)
        val viewModel = CategoryManagementViewModel(repository)
        assertTrue(viewModel.uiState.value.isLoading)
        start(viewModel)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(listOf(mine, builtIn), viewModel.uiState.value.entries)
    }

    @Test
    fun カテゴリを作り_直し_消すと知らせる() = runTest {
        repository.entries.value = listOf(builtIn)
        val viewModel = CategoryManagementViewModel(repository)
        val messages = start(viewModel)

        viewModel.add("旅行", "行き先や宿")
        advanceUntilIdle()
        val created = viewModel.uiState.value.entries.single { it.kind == CategoryKind.USER }
        viewModel.update(created, "出張", "移動")
        advanceUntilIdle()
        viewModel.delete(viewModel.uiState.value.entries.single { it.kind == CategoryKind.USER })
        advanceUntilIdle()

        assertEquals(
            listOf<CategoryManagementMessage>(
                CategoryManagementMessage.Added("旅行"),
                CategoryManagementMessage.Updated("出張"),
                CategoryManagementMessage.Deleted("出張")
            ),
            messages
        )
        assertEquals(listOf(builtIn), viewModel.uiState.value.entries)
    }

    @Test
    fun 保存できなかった理由を知らせる() = runTest {
        val viewModel = CategoryManagementViewModel(repository)
        val messages = start(viewModel)

        repository.result = CategoryResult.Duplicate("技術")
        viewModel.add("技術", "")
        advanceUntilIdle()
        repository.result = CategoryResult.Invalid("カテゴリ名を入力してください")
        viewModel.add("", "")
        advanceUntilIdle()

        assertEquals(
            listOf<CategoryManagementMessage>(
                CategoryManagementMessage.Failed("「技術」と同じ名前のカテゴリがあります"),
                CategoryManagementMessage.Failed("カテゴリ名を入力してください")
            ),
            messages
        )
    }

    @Test
    fun 標準のカテゴリは直せず消せない() = runTest {
        repository.entries.value = listOf(builtIn)
        val viewModel = CategoryManagementViewModel(repository)
        val messages = start(viewModel)

        viewModel.update(builtIn, "別名", "")
        viewModel.delete(builtIn)
        advanceUntilIdle()

        assertTrue(messages.isEmpty())
        assertTrue(repository.deletedIds.isEmpty())
    }
}
