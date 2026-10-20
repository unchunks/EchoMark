package com.unchunks.echomark.ui.tags

import com.unchunks.echomark.domain.model.TagWithCount
import com.unchunks.echomark.testing.FakeTagRepository
import com.unchunks.echomark.testing.MainDispatcherRule
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
class TagManagementViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repository = FakeTagRepository()
    private val kotlin = TagWithCount(1, "kotlin", 3)
    private val kotlinJa = TagWithCount(2, "Kotlin言語", 2)

    private fun TestScope.start(viewModel: TagManagementViewModel): MutableList<TagManagementMessage> {
        val messages = mutableListOf<TagManagementMessage>()
        backgroundScope.launch { viewModel.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.messages.toList(messages) }
        return messages
    }

    @Test
    fun 件数つきのタグ一覧が出る() = runTest {
        repository.tags.value = listOf(kotlin, kotlinJa)
        val viewModel = TagManagementViewModel(repository)
        assertTrue(viewModel.uiState.value.isLoading)
        start(viewModel)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(listOf(kotlin, kotlinJa), viewModel.uiState.value.tags)
    }

    @Test
    fun 名前変更と既存名への統合で知らせる内容が変わる() = runTest {
        repository.tags.value = listOf(kotlin, kotlinJa)
        val viewModel = TagManagementViewModel(repository)
        val messages = start(viewModel)

        viewModel.rename(kotlinJa, " kotlin ")
        advanceUntilIdle()
        assertEquals(listOf(TagWithCount(1, "kotlin", 5)), viewModel.uiState.value.tags)

        viewModel.rename(TagWithCount(1, "kotlin", 5), "Kotlin")
        advanceUntilIdle()

        assertEquals(
            listOf(
                TagManagementMessage.Merged("Kotlin言語", "kotlin"),
                TagManagementMessage.Renamed("Kotlin")
            ),
            messages
        )
    }

    @Test
    fun 同じ名前なら何も知らせず空の名前は失敗を知らせる() = runTest {
        repository.tags.value = listOf(kotlin)
        val viewModel = TagManagementViewModel(repository)
        val messages = start(viewModel)

        viewModel.rename(kotlin, "kotlin")
        viewModel.rename(kotlin, "  ")
        advanceUntilIdle()

        assertEquals(listOf<TagManagementMessage>(TagManagementMessage.Failed("タグ名を入力してください")), messages)
    }

    @Test
    fun 削除するとタグが消えて知らせる() = runTest {
        repository.tags.value = listOf(kotlin, kotlinJa)
        val viewModel = TagManagementViewModel(repository)
        val messages = start(viewModel)

        viewModel.delete(kotlin)
        advanceUntilIdle()

        assertEquals(listOf(1L), repository.deletedIds)
        assertEquals(listOf(kotlinJa), viewModel.uiState.value.tags)
        assertEquals(listOf<TagManagementMessage>(TagManagementMessage.Deleted("kotlin")), messages)
    }
}
