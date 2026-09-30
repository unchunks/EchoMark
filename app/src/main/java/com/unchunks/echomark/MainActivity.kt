package com.unchunks.echomark

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.unchunks.echomark.ui.navigation.EchoMarkNavHost
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepSplashUntilReady()
        enableEdgeToEdge()
        setContent {
            val uiState by viewModel.uiState.collectAsState()
            // 設定の読み込み前は何も描かない(スプラッシュが出ている)
            val ready = uiState as? MainUiState.Ready ?: return@setContent
            val darkTheme = ready.themeMode.isDark(isSystemInDarkTheme())

            // ステータスバー・ナビゲーションバーのアイコン色を、端末ではなくアプリのテーマに合わせる
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { darkTheme }
                )
                onDispose {}
            }

            EchoMarkTheme(darkTheme = darkTheme, dynamicColor = ready.dynamicColor) {
                EchoMarkNavHost()
            }
        }
    }

    /**
     * 設定(テーマ・オンボーディング済みか)を読み終えるまで最初の描画を止め、スプラッシュを出し続ける。
     * 読み込み前に既定の配色や一覧画面が一瞬見えるのを防ぐ(minSdk 31 なので OS のスプラッシュ API をそのまま使う)。
     */
    private fun keepSplashUntilReady() {
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (viewModel.uiState.value !is MainUiState.Ready) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
    }

    private companion object {
        // 3ボタンナビゲーションのときの半透明の背景(enableEdgeToEdge の既定値と同じ)
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
