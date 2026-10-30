package com.unchunks.echomark.ui.tags

import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.echomark.domain.model.TagWithCount
import com.unchunks.echomark.testing.FakeTagRepository
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


    @Test
    fun 表記ゆれの候補を一覧と一緒に返す() = runTest {
        val upper = TagWithCount(3, "Android", 5)
        val lower = TagWithCount(4, "android", 1)
        repository.tags.value = listOf(kotlin, upper, lower)
        val viewModel = TagManagementViewModel(repository)
        start(viewModel)
        advanceUntilIdle()

        assertEquals(listOf(listOf(upper, lower)), viewModel.uiState.value.variantGroups)
    }

    @Test
    fun 他のタグに統合すると付け替えて知らせる() = runTest {
        repository.tags.value = listOf(kotlin, kotlinJa)
        val viewModel = TagManagementViewModel(repository)
        val messages = start(viewModel)

        viewModel.merge(kotlinJa, kotlin)
        advanceUntilIdle()

        assertEquals(listOf(1L to listOf(2L)), repository.mergeCalls)
        assertEquals(listOf(TagWithCount(1, "kotlin", 5)), viewModel.uiState.value.tags)
        assertEquals(listOf<TagManagementMessage>(TagManagementMessage.Merged("Kotlin言語", "kotlin")), messages)
    }

    @Test
    fun 表記ゆれの候補を選んだ名前の1つにまとめる() = runTest {
        val upper = TagWithCount(3, "Android", 5)
        val lower = TagWithCount(4, "android", 1)
        val full = TagWithCount(5, "ＡＮＤＲＯＩＤ", 2)
        repository.tags.value = listOf(upper, lower, full)
        val viewModel = TagManagementViewModel(repository)
        val messages = start(viewModel)
        advanceUntilIdle()

        viewModel.unify(viewModel.uiState.value.variantGroups.single(), into = lower)
        advanceUntilIdle()

        assertEquals(listOf(4L to listOf(3L, 5L)), repository.mergeCalls)
        assertEquals(listOf(TagWithCount(4, "android", 8)), viewModel.uiState.value.tags)
        assertTrue(viewModel.uiState.value.variantGroups.isEmpty())
        assertEquals(listOf<TagManagementMessage>(TagManagementMessage.Unified(2, "android")), messages)
    }

    @Test
    fun 統合先が無くなっていたら何も知らせない() = runTest {
        repository.tags.value = listOf(kotlin)
        val viewModel = TagManagementViewModel(repository)
        val messages = start(viewModel)

        viewModel.merge(kotlin, kotlinJa)
        advanceUntilIdle()

        assertTrue(messages.isEmpty())
    }


    @Test
    fun 親のタグを設定し最上位に戻すと知らせる() = runTest {
        val parent = TagWithCount(3, "プログラミング", 0)
        val child = TagWithCount(4, "Android", 2)
        repository.tags.value = listOf(parent, child)
        val viewModel = TagManagementViewModel(repository)
        val messages = start(viewModel)

        viewModel.setParent(child, parent)
        advanceUntilIdle()
        assertEquals(parent.id, viewModel.uiState.value.tags.single { it.id == child.id }.parentId)

        viewModel.setParent(child.copy(parentId = parent.id), null)
        advanceUntilIdle()

        assertEquals(
            listOf<TagManagementMessage>(
                TagManagementMessage.ParentChanged("Android", "プログラミング"),
                TagManagementMessage.ParentChanged("Android", null)
            ),
            messages
        )
    }

    @Test
    fun 自分の子を親にしようとすると失敗を知らせる() = runTest {
        val parent = TagWithCount(3, "プログラミング", 0)
        val child = TagWithCount(4, "Android", 2, parentId = 3)
        repository.tags.value = listOf(parent, child)
        val viewModel = TagManagementViewModel(repository)
        val messages = start(viewModel)

        viewModel.setParent(parent, child)
        advanceUntilIdle()

        assertEquals(listOf<TagManagementMessage>(TagManagementMessage.Failed("自分の子のタグは親にできません")), messages)
        assertEquals(null, viewModel.uiState.value.tags.single { it.id == parent.id }.parentId)
    }
}
