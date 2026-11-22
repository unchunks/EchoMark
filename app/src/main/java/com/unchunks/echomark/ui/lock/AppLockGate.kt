package com.unchunks.echomark.ui.lock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.withResumed
import com.unchunks.components.applock.AppLockAuthenticator
import com.unchunks.components.applock.AppLockManager
import com.unchunks.components.applock.AppLockState
import com.unchunks.components.applock.findFragmentActivity
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

/**
 * アプリのロック([AppLockManager])に従って、[content] を隠す。アプリのすべての Activity の setContent の中で、テーマの内側に置く。
 *
 * - ロックを解くまでは [content] を組まない(ViewModel も作らず、データを読まない)
 * - 一度解いたあとにまたロックしたときは、[content] を残したまま、上に不透明なロックの画面を重ねる
 *   (外したままにすると、ファイルの選択などから戻った結果を受け取れなくなるため)。下の中身は読み上げの対象から外し、
 *   入力中の文字はキーボードごと閉じる。戻るボタンでは中身に戻らず、アプリを離れる
 * - ロックの画面を出したら、すぐに本人を確かめる画面(指紋・顔・端末の PIN など)を出す
 */
@Composable
fun AppLockGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, AppLockEntryPoint::class.java)
    }
    val manager = entryPoint.appLockManager()
    val state by manager.state.collectAsState()
    // 解いたことがあるか(この Activity の中だけ。プロセスが終われば、次は必ず解くところから)
    var everOpened by remember { mutableStateOf(false) }
    if (state == AppLockState.OPEN) everOpened = true
    val locked = state == AppLockState.LOCKED

    val activity = remember(context) { context.findFragmentActivity() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var noDeviceLock by remember { mutableStateOf(false) }

    fun unlock() {
        val host = activity ?: return
        when (AppLockAuthenticator.availability(host)) {
            AppLockAuthenticator.Availability.AVAILABLE -> {
                noDeviceLock = false
                manager.onAuthenticationStarted()
                AppLockAuthenticator.authenticate(host, title = "EchoMark のロックを解除") { result ->
                    manager.onAuthenticationFinished(result is AppLockAuthenticator.Result.Succeeded)
                    message = (result as? AppLockAuthenticator.Result.Failed)?.message
                    noDeviceLock = result is AppLockAuthenticator.Result.NoDeviceLock
                }
            }
            AppLockAuthenticator.Availability.NO_DEVICE_LOCK -> {
                noDeviceLock = true
                message = null
            }
        }
    }

    LaunchedEffect(locked) {
        if (!locked) return@LaunchedEffect
        // 下の中身に入力していた文字が、見えないまま打てる状態で残らないようにする
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        // 画面が前に出てから(本人を確かめる画面は、前に出ている Activity にしか出せない)
        lifecycle.withResumed { unlock() }
    }

    Box(Modifier.fillMaxSize()) {
        if (everOpened) {
            Box(if (locked) Modifier.clearAndSetSemantics { } else Modifier) { content() }
        }
        if (locked) {
            BackHandler { activity?.moveTaskToBack(true) }
            AppLockContent(
                message = message,
                noDeviceLock = noDeviceLock,
                onUnlock = ::unlock,
                onTurnOff = {
                    // 端末の画面ロックが無いと、本人を確かめられない(画面ロックを外せるのは、それを知っている人だけ)
                    scope.launch { entryPoint.appSettings().updateAppLock { it.copy(enabled = false) } }
                }
            )
        }
    }
}

/**
 * ロック中の画面(状態を受け取って描くだけ。スクリーンショットテストで描ける)。下の中身を触れないよう、不透明で、すべての操作を受け止める。
 * @param noDeviceLock 端末に画面ロック(PIN など)も生体認証も無く、本人を確かめられない
 */
@Composable
fun AppLockContent(
    message: String?,
    noDeviceLock: Boolean,
    onUnlock: () -> Unit,
    onTurnOff: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            // 下の中身に触れさせない
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.systemBarsPadding().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                "EchoMark はロックされています",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                if (noDeviceLock) {
                    "この端末には画面ロック(PIN・パターン・パスワード)も指紋・顔の登録も無いため、本人を確かめられません。" +
                        "端末の設定で画面ロックを設定するか、アプリのロックをオフにして開いてください。"
                } else {
                    message ?: "指紋・顔、または端末の画面ロック(PIN など)でロックを解除してください。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (noDeviceLock) {
                OutlinedButton(onClick = onTurnOff) { Text("アプリのロックをオフにして開く") }
            } else {
                Button(onClick = onUnlock) { Text("ロックを解除") }
            }
        }
    }
}

/** Compose から [AppLockManager] を取る(Activity ごとに注入しなくて済むように) */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppLockEntryPoint {
    fun appLockManager(): AppLockManager
    fun appSettings(): AppSettingsRepository
}

/**
 * アプリのロックをオンにする前に、本人を確かめる手順を返す(締め出されないよう、確かめられる端末かを先に試す)。
 * 確かめられたら [onConfirmed]、端末に画面ロックも生体認証も無ければ [onUnavailable]。やめたときは何もしない。
 */
@Composable
fun rememberAppLockConfirmation(onConfirmed: () -> Unit, onUnavailable: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val manager = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, AppLockEntryPoint::class.java).appLockManager()
    }
    val confirmed by rememberUpdatedState(onConfirmed)
    val unavailable by rememberUpdatedState(onUnavailable)
    return remember(context, manager) {
        {
            val activity = context.findFragmentActivity()
            if (activity == null || AppLockAuthenticator.availability(activity) != AppLockAuthenticator.Availability.AVAILABLE) {
                unavailable()
            } else {
                manager.onAuthenticationStarted()
                AppLockAuthenticator.authenticate(activity, title = "アプリのロックをオンにする") { result ->
                    val succeeded = result is AppLockAuthenticator.Result.Succeeded
                    manager.onAuthenticationFinished(succeeded)
                    when {
                        succeeded -> confirmed()
                        result is AppLockAuthenticator.Result.NoDeviceLock -> unavailable()
                    }
                }
            }
        }
    }
}
