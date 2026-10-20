package com.unchunks.echomark.ui.share

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.unchunks.echomark.MainUiState
import com.unchunks.echomark.MainViewModel
import com.unchunks.echomark.isDark
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay

/**
 * 他アプリの共有メニューから受け取ってクイック保存するActivity。
 * テキスト・URL(ACTION_SEND, text/plain)と、ファイル(画像・PDF・音声・動画・テキストファイル。
 * ACTION_SEND / ACTION_SEND_MULTIPLE の EXTRA_STREAM)を受け付ける。
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    private val viewModel: ShareViewModel by viewModels()

    /** テーマ設定の読み込み(アプリ本体と同じ) */
    private val appViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // ファイル名・サイズ・種類を提供元に問い合わせる(プレビューと保存前の確認に使う)
        val shared = parseSharedContent(intent)?.withFileDetails(contentResolver)
        if (shared == null) {
            Toast.makeText(this, "保存できる内容がありません", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

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
                // 保存できたら、シート内で結果を短く見せてから閉じる
                LaunchedEffect(status) {
                    if (status is ShareSaveStatus.Saved) {
                        // 保存できなかったファイルがあるときは、読めるよう長めに見せる
                        delay(if ((status as ShareSaveStatus.Saved).failedCount > 0) PARTIAL_SAVED_DISPLAY_MILLIS else SAVED_DISPLAY_MILLIS)
                        finish()
                    }
                }
                QuickSaveSheet(
                    shared = shared,
                    status = status,
                    onSave = { title, memo, tags -> viewModel.save(shared, title, memo, tags) },
                    onDismiss = { finish() }
                )
            }
        }
    }

    private companion object {
        const val SAVED_DISPLAY_MILLIS = 1_200L
        const val PARTIAL_SAVED_DISPLAY_MILLIS = 3_000L
    }
}
