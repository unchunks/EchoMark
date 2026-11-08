package com.unchunks.echomark.ui.detail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 要約の根拠の表示(本文との突き合わせを画面のスレッドの外で行い、終わったら結果を出すこと)。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class EvidenceSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 本文との突き合わせが終わると根拠を出す() {
        composeRule.setContent {
            EchoMarkTheme {
                EvidenceSection(summary = "東京のラーメン店を紹介する。", body = "東京のラーメン店を紹介する記事です。")
            }
        }

        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodes(hasText("要約の根拠(本文から探した一節)")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("要約の根拠(本文から探した一節)").assertIsDisplayed()
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
    }
}
