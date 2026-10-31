package com.unchunks.echomark.ui.share

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.unchunks.echomark.MainUiState
import com.unchunks.echomark.MainViewModel
import com.unchunks.echomark.isDark
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import kotlinx.coroutines.delay

/**
 * クイック保存のシート([QuickSaveSheet])を、透明な Activity の上に出す。
 * 共有([ShareReceiverActivity])と、クイック設定のタイルから開くクリップボードの保存([ClipboardSaveActivity])で使う。
 * 保存できたら、シート内で結果を短く見せてから閉じる。
 */
internal fun ComponentActivity.setQuickSaveContent(
    shared: SharedContent,
    viewModel: ShareViewModel,
    appViewModel: MainViewModel
) {
    setContent {
        // アプリのテーマ設定(ライト/ダーク・ダイナミックカラー)に合わせる。読み込み前(ほんの一瞬)は何も描かない
        val appState by appViewModel.uiState.collectAsState()
        val ready = appState as? MainUiState.Ready ?: return@setContent
        val darkTheme = ready.themeMode.isDark(isSystemInDarkTheme())
        // ナビゲーションバーのアイコン色も、端末ではなくアプリのテーマに合わせる
        DisposableEffect(darkTheme) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme }
            )
            onDispose {}
        }
        EchoMarkTheme(darkTheme = darkTheme, dynamicColor = ready.dynamicColor) {
            val status by viewModel.status.collectAsState()
            val suggestedTags by viewModel.suggestedTags.collectAsState()
            LaunchedEffect(status) {
                if (status is ShareSaveStatus.Saved) {
                    // 保存できなかったものがあるときは、読めるよう長めに見せる
                    delay(if ((status as ShareSaveStatus.Saved).failedCount > 0) PARTIAL_SAVED_DISPLAY_MILLIS else SAVED_DISPLAY_MILLIS)
                    finish()
                }
            }
            QuickSaveSheet(
                shared = shared,
                status = status,
                suggestedTags = suggestedTags,
                onInputChange = { title, memo -> viewModel.updateSuggestions(shared, title, memo) },
                onSave = { title, memo, tags -> viewModel.save(shared, title, memo, tags) },
                onDismiss = { finish() }
            )
        }
    }
}

private const val SAVED_DISPLAY_MILLIS = 1_200L
private const val PARTIAL_SAVED_DISPLAY_MILLIS = 3_000L
