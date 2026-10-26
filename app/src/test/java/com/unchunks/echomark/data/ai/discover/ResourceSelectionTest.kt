package com.unchunks.echomark.data.ai.discover

import com.unchunks.echomark.data.remote.PageLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceSelectionTest {

    private val candidates = listOf(
        PageLink("https://example.com/1", "Kotlin 入門"),
        PageLink("https://example.com/2", "ログイン"),
        PageLink("https://example.com/3", "Compose の使い方")
    )

    @Test
    fun 指示には調べたいことと番号つきの候補を入れる() {
        val prompt = ResourceSelection.prompt("Kotlin を学ぶ", candidates)

        assertTrue(prompt, prompt.contains("調べたいこと: Kotlin を学ぶ"))
        assertTrue(prompt, prompt.contains("[1] Kotlin 入門"))
        assertTrue(prompt, prompt.contains("[3] Compose の使い方"))
        assertTrue(prompt, prompt.contains("{\"picks\": [番号, 番号]}"))
    }

    @Test
    fun 応答の番号を0始まりの位置にする() {
        assertEquals(listOf(0, 2), ResourceSelection.parsePicks("{\"picks\": [1, 3]}", candidates.size))
    }

    @Test
    fun 前後に文があってもJSONを取り出す() {
        assertEquals(listOf(2), ResourceSelection.parsePicks("選びました: {\"picks\": [3]} 以上です", candidates.size))
    }

    @Test
    fun 範囲外と重複は捨て_上限まで選ぶ() {
        assertEquals(listOf(0), ResourceSelection.parsePicks("{\"picks\": [0, 1, 1, 9, -2]}", candidates.size))
        assertEquals(listOf(0, 1), ResourceSelection.parsePicks("{\"picks\": [1, 2, 3]}", candidates.size, maxPicks = 2))
    }

    @Test
    fun 形が崩れていれば何も選ばない() {
        assertEquals(emptyList<Int>(), ResourceSelection.parsePicks("役立つものはありません", candidates.size))
        assertEquals(emptyList<Int>(), ResourceSelection.parsePicks("{\"picks\": [1, 2", candidates.size))
        assertEquals(emptyList<Int>(), ResourceSelection.parsePicks("{\"other\": [1]}", candidates.size))
    }
}
