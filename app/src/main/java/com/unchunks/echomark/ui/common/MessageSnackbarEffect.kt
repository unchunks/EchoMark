package com.unchunks.echomark.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * ViewModel が状態(StateFlow など)で持つ Snackbar の文言を、一度きりの知らせとして出す。
 *
 * 表示する前に [onShown] で消費済みにする。表示が終わってから消費すると、表示中に別の画面へ移ったとき
 * (表示が中断されて消費されない)に、戻ってきたところで同じ知らせが出し直されてしまうため。
 * 文言をキーにした LaunchedEffect では、消費した時点でキーが変わって表示が打ち切られるので、ここでは購読し続ける。
 */
@Composable
fun MessageSnackbarEffect(
    messages: Flow<String?>,
    snackbarHostState: SnackbarHostState,
    onShown: () -> Unit
) {
    val currentOnShown by rememberUpdatedState(onShown)
    LaunchedEffect(messages, snackbarHostState) {
        messages.filterNotNull().collect { message ->
            currentOnShown()
            // 新しい知らせを優先する。表示待ちで次の知らせを止めないよう別コルーチンで出す
            snackbarHostState.currentSnackbarData?.dismiss()
            launch { snackbarHostState.showSnackbar(message) }
        }
    }
}
