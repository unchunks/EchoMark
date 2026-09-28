package com.unchunks.echomark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.unchunks.echomark.ui.navigation.EchoMarkNavHost
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EchoMarkTheme {
                EchoMarkNavHost()
            }
        }
    }
}
