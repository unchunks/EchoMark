package com.unchunks.echomark.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.repository.DesignStyle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 設定の「デザイン」を切り替えたときのテーマの振る舞い。 */
@RunWith(AndroidJUnit4::class)
class EchoMarkThemeTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * デザインが変わっても配色・文字・角丸の値だけを替え、中身の状態は作り直さない。
     * 作り直すと、設定画面でデザインを選んだ瞬間にナビゲーションの履歴が消えて一覧へ戻ってしまう。
     */
    @Test
    fun デザインを切り替えても画面の状態を保つ() {
        var style by mutableStateOf(DesignStyle.CLASSIC)
        var shapes: Shapes? = null
        var provided: DesignStyle? = null
        composeRule.setContent {
            EchoMarkTheme(darkTheme = false, designStyle = style) {
                var count by remember { mutableIntStateOf(0) }
                shapes = MaterialTheme.shapes
                provided = LocalDesignStyle.current
                TextButton(onClick = { count++ }) { Text("count=$count") }
            }
        }
        composeRule.onNodeWithText("count=0").performClick()
        composeRule.onNodeWithText("count=1").assertExists()

        DesignStyle.entries.forEach { next ->
            style = next
            composeRule.waitForIdle()
            composeRule.onNodeWithText("count=1").assertExists()
            assertEquals(next, provided)
            assertEquals(designShapes(next), shapes)
        }
    }
}
