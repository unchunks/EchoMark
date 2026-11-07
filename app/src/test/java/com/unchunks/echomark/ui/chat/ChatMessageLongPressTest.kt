package com.unchunks.echomark.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 長押しの途中で画面が描き直されても(ストリーミング中は回答の更新のたびに描き直される)、長押しのコピーが中断されないこと。
 */
@RunWith(AndroidJUnit4::class)
class ChatMessageLongPressTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 長押し中に描き直されてもコピーできる() {
        var tick by mutableIntStateOf(0)
        val copied = mutableListOf<Int>()
        composeRule.setContent {
            EchoMarkTheme {
                val current = tick
                // 描き直すたびに新しいラムダを渡す(ChatScreen と同じ渡し方)
                UserMessageItem(text = "過去の質問", onCopy = { copied += current })
            }
        }
        composeRule.mainClock.autoAdvance = false

        composeRule.onNodeWithText("過去の質問").performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(100)
        // ストリーミングで別のメッセージが更新され、この行も描き直された
        tick = 1
        Snapshot.sendApplyNotifications()
        composeRule.mainClock.advanceTimeBy(100)
        tick = 2
        Snapshot.sendApplyNotifications()
        composeRule.mainClock.advanceTimeBy(LONG_PRESS_WAIT_MILLIS)
        composeRule.onNodeWithText("過去の質問").performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(100)

        // 最新のコピー処理が1回だけ呼ばれる
        assertEquals(listOf(2), copied)
    }

    private companion object {
        /** 長押しと判定されるまでの時間(既定 400ms)より十分長く */
        const val LONG_PRESS_WAIT_MILLIS = 1_000L
    }
}
