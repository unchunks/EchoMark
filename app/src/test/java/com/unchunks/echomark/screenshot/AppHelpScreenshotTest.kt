package com.unchunks.echomark.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.ui.help.AppHelpActions
import com.unchunks.echomark.ui.help.AppHelpContent
import com.unchunks.echomark.ui.help.AppHelpError
import com.unchunks.echomark.ui.help.AppHelpMessage
import com.unchunks.echomark.ui.help.AppHelpUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** 使い方を AI に聞く画面(スマートフォン1画面分の高さで撮る)。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppHelpScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val conversation = listOf(
        AppHelpMessage(1, fromUser = true, text = "記事を保存するには?"),
        AppHelpMessage(
            2,
            fromUser = false,
            text = "ブラウザで共有メニューを開き、EchoMark を選ぶと保存できます。保存すると要約とタグ付けが自動で始まります。",
            sourceTitles = listOf("ブラウザなどから保存する", "アプリの中から追加する")
        )
    )

    @Test
    fun empty() = screenshot.captureLightDark("app_help_empty") {
        Box(Modifier.height(800.dp)) { AppHelpContent(AppHelpUiState(), AppHelpActions()) }
    }

    @Test
    fun answered() = screenshot.captureLightDark("app_help_answered") {
        Box(Modifier.height(800.dp)) { AppHelpContent(AppHelpUiState(messages = conversation), AppHelpActions()) }
    }

    @Test
    fun answering() = screenshot.captureLightDark("app_help_answering") {
        Box(Modifier.height(800.dp)) {
            AppHelpContent(
                AppHelpUiState(
                    messages = conversation + AppHelpMessage(3, fromUser = true, text = "要約が作られないときは?") +
                        AppHelpMessage(4, fromUser = false, text = "", sourceTitles = listOf("要約が作られない・失敗したとき")),
                    answering = true
                ),
                AppHelpActions()
            )
        }
    }

    @Test
    fun modelMissing() = screenshot.captureLightDark("app_help_model_missing") {
        Box(Modifier.height(800.dp)) {
            AppHelpContent(
                AppHelpUiState(
                    messages = listOf(AppHelpMessage(1, fromUser = true, text = "記事を保存するには?")),
                    error = AppHelpError("端末内 AI モデルが未取り込みです。設定 > AI 設定 でモデルを取り込んでください", needsAiSettings = true)
                ),
                AppHelpActions()
            )
        }
    }
}
