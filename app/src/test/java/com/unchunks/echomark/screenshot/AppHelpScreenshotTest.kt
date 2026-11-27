package com.unchunks.echomark.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.llm.ApiProvider
import com.unchunks.echomark.domain.help.AppAction
import com.unchunks.echomark.domain.help.AppScreen
import com.unchunks.echomark.domain.repository.ThemeMode
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

    /** チャットをクラウド API にしているとき: 質問の送り先を書く */
    @Test
    fun emptyCloud() = screenshot.captureLightDark("app_help_empty_cloud") {
        Box(Modifier.height(800.dp)) {
            AppHelpContent(AppHelpUiState(cloudProvider = ApiProvider.GEMINI), AppHelpActions())
        }
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

    /** 操作のボタン(画面を開く・設定を変える)と、実行した結果の知らせ */
    @Test
    fun actions() = screenshot.captureLightDark("app_help_actions") {
        Box(Modifier.height(800.dp)) {
            AppHelpContent(
                AppHelpUiState(
                    messages = listOf(
                        AppHelpMessage(1, fromUser = true, text = "ダークモードにして"),
                        AppHelpMessage(
                            2,
                            fromUser = false,
                            text = "テーマをダークにします。",
                            sourceTitles = listOf("表示(テーマ・デザイン)"),
                            actions = listOf(AppAction.SetTheme(ThemeMode.DARK), AppAction.Open(AppScreen.SETTINGS))
                        ),
                        AppHelpMessage(3, fromUser = true, text = "https://example.com/article を保存して"),
                        AppHelpMessage(4, fromUser = false, text = "保存します。", actions = listOf(AppAction.Open(AppScreen.ADD_BOOKMARK))),
                        AppHelpMessage(5, fromUser = false, text = "リンクを保存しました。要約とタグ付けを始めます", isResult = true)
                    )
                ),
                AppHelpActions()
            )
        }
    }
}
