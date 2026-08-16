package com.unchunks.echomark

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.ui.bookmark.BookmarkListScreen
import com.unchunks.echomark.ui.chat.ChatScreen
import com.unchunks.echomark.ui.settings.SettingsScreen
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { /* 拒否されても、通知が出ないだけでアプリの他機能には影響しない */ }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            EchoMarkTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background // テーマの背景色を指定
                ) {
                    // TODO: 戻るで前の画面に戻れるようにバックスタックを積むようにする
                    var currentScreen by remember { mutableStateOf(Screen.BOOKMARKS) }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .systemBarsPadding()
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                            TextButton(onClick = {
                                currentScreen = Screen.BOOKMARKS
                            }) {
                                Text("ブックマーク")
                            }
                            TextButton(onClick = {
                                currentScreen = Screen.CHAT
                            }) {
                                Text("チャット")
                            }
                            TextButton(onClick = {
                                currentScreen = Screen.SETTINGS
                            }) {
                                Text("設定")
                            }
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            when (currentScreen) {
                                Screen.BOOKMARKS -> BookmarkListScreen()
                                Screen.CHAT -> ChatScreen()
                                Screen.SETTINGS -> SettingsScreen()
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class Screen {
    BOOKMARKS,
    CHAT,
    SETTINGS
}
