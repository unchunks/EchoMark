package com.unchunks.echomark.ui.selection

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionHolderTest {

    private val handle = SavedStateHandle()
    private val holder = SelectionHolder(handle)

    @Test
    fun 最初は選択モードではない() {
        assertEquals(SelectionState(), holder.state.value)
    }

    @Test
    fun 長押しで入ると_そのものを選んだ状態で始まる() {
        holder.enter(5)

        assertTrue(holder.state.value.active)
        assertEquals(setOf(5L), holder.state.value.ids)
        assertEquals(1, holder.state.value.count)
    }

    @Test
    fun メニューから入ると何も選んでいない() {
        holder.enter()

        assertTrue(holder.state.value.active)
        assertEquals(emptySet<Long>(), holder.state.value.ids)
    }

    @Test
    fun すでに選択モードのときの長押しは選びを足す() {
        holder.enter(1)
        holder.enter(2)

        assertEquals(setOf(1L, 2L), holder.state.value.ids)
    }

    @Test
    fun 切り替えは選ぶと外すを繰り返し_選択モードの外では何もしない() {
        holder.toggle(1)
        assertFalse(holder.state.value.active)
        assertEquals(emptySet<Long>(), holder.state.value.ids)

        holder.enter()
        holder.toggle(1)
        holder.toggle(2)
        holder.toggle(1)

        assertEquals(setOf(2L), holder.state.value.ids)
    }

    @Test
    fun すべて選ぶと渡したものだけになり_選びを解いても選択モードのまま() {
        holder.enter(9)

        holder.selectAll(listOf(1, 2, 3))
        assertEquals(setOf(1L, 2L, 3L), holder.state.value.ids)

        holder.clear()
        assertTrue(holder.state.value.active)
        assertEquals(emptySet<Long>(), holder.state.value.ids)
    }

    @Test
    fun やめると選びも消える() {
        holder.enter(1)

        holder.exit()

        assertEquals(SelectionState(), holder.state.value)
    }

    @Test
    fun 同じ保存状態から作り直すと選択が戻る() {
        holder.enter(1)
        holder.toggle(4)

        // 回転・プロセスの再作成のあとの ViewModel を想定
        val restored = SelectionHolder(handle)

        assertEquals(SelectionState(active = true, ids = setOf(1L, 4L)), restored.state.value)
    }

    @Test
    fun やめたあとに作り直しても選択モードは戻らない() {
        holder.enter(1)
        holder.exit()

        assertEquals(SelectionState(), SelectionHolder(handle).state.value)
    }
}
