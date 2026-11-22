package com.unchunks.echomark.ui.selection

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 一覧の複数選択の状態。
 * @param active 選択モードか(1件も選んでいなくても、メニューの「選択」で入った直後は true)
 * @param ids 選んでいるブックマークの ID
 */
data class SelectionState(val active: Boolean = false, val ids: Set<Long> = emptySet()) {
    val count: Int get() = ids.size
}

/**
 * 選択の状態を持ち、回転やプロセスの再作成のあとも [SavedStateHandle] から戻す。
 * 一覧の画面(検索結果・スマートフォルダを含む)とコレクションの画面の ViewModel が、それぞれ持つ。
 * 選んだものが今の一覧に無いとき(絞り込みや検索で外れた)の扱いは、使う側が決める(ここでは ID を持つだけ)。
 */
class SelectionHolder(private val handle: SavedStateHandle) {

    private val mutableState = MutableStateFlow(
        SelectionState(
            active = handle.get<Boolean>(KEY_ACTIVE) ?: false,
            ids = handle.get<LongArray>(KEY_IDS)?.toSet().orEmpty()
        )
    )

    val state: StateFlow<SelectionState> = mutableState

    /** 選択モードに入る。[id] を渡すと、それを選んだ状態で始める(長押し) */
    fun enter(id: Long? = null) {
        update { if (it.active) it.copy(ids = if (id != null) it.ids + id else it.ids) else SelectionState(true, setOfNotNull(id)) }
    }

    /** [id] の選択を切り替える。選択モードでなければ何もしない */
    fun toggle(id: Long) {
        update { if (!it.active) it else it.copy(ids = if (id in it.ids) it.ids - id else it.ids + id) }
    }

    /** [ids] をすべて選ぶ(今の一覧に出ているものすべて) */
    fun selectAll(ids: Collection<Long>) {
        update { if (!it.active) it else it.copy(ids = ids.toSet()) }
    }

    /** 選びを解く(選択モードのまま) */
    fun clear() {
        update { it.copy(ids = emptySet()) }
    }

    /** 選択モードを終える */
    fun exit() {
        update { SelectionState() }
    }

    private fun update(transform: (SelectionState) -> SelectionState) {
        val next = transform(mutableState.value)
        if (next == mutableState.value) return
        mutableState.value = next
        handle[KEY_ACTIVE] = next.active
        handle[KEY_IDS] = next.ids.toLongArray()
    }

    private companion object {
        const val KEY_ACTIVE = "selection_active"
        const val KEY_IDS = "selection_ids"
    }
}
