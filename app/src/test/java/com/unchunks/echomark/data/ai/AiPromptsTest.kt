package com.unchunks.echomark.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 解析の指示に既存のタグを入れる量と書き方。 */
class AiPromptsTest {

    @Test
    fun 既存のタグを優先順のまま指示に入れて使い回させる() {
        val prompt = AiPrompts.analyzeInstructions(listOf("Android", "読書"))

        assertTrue(prompt, prompt.contains("既存のタグ:\n[\"Android\", \"読書\"]"))
        assertTrue(prompt, prompt.contains("表記を変えずにそのまま使って"))
        assertTrue(prompt, prompt.contains("1〜${AiPrompts.MAX_TAGS}個"))
    }

    @Test
    fun 既存のタグが無ければ既存タグの指示を入れない() {
        val prompt = AiPrompts.analyzeInstructions(emptyList())

        assertFalse(prompt, prompt.contains("既存のタグ"))
    }

    @Test
    fun 個数と文字数の予算に収まる分だけ先頭から選ぶ() {
        val tags = (1..10).map { "tag$it" }

        assertEquals(tags.take(3), AiPrompts.existingTagsForPrompt(tags, maxCount = 3, maxChars = 1_000))
        // "tag1" は引用符と区切りを含めて 8 文字分。20 文字なら 2 個まで
        assertEquals(tags.take(2), AiPrompts.existingTagsForPrompt(tags, maxCount = 50, maxChars = 20))
    }

    @Test
    fun 空や長すぎるタグ名は飛ばす() {
        val long = "あ".repeat(31)

        assertEquals(
            listOf("a", "b"),
            AiPrompts.existingTagsForPrompt(listOf("a", " ", long, "b"), maxCount = 10, maxChars = 1_000)
        )
    }

    @Test
    fun ローカル向けはクラウド向けより少なく入れる() {
        val tags = (1..100).map { "タグ$it" }
        val local = AiPrompts.existingTagsForPrompt(
            tags, AiPrompts.LOCAL_MAX_EXISTING_TAGS, AiPrompts.LOCAL_MAX_EXISTING_TAG_CHARS
        )
        val api = AiPrompts.existingTagsForPrompt(
            tags, AiPrompts.API_MAX_EXISTING_TAGS, AiPrompts.API_MAX_EXISTING_TAG_CHARS
        )

        assertTrue(local.size < api.size)
        assertTrue(api.size <= AiPrompts.API_MAX_EXISTING_TAGS)
    }

    @Test
    fun 引用符を含むタグ名でも配列の形を崩さない() {
        val prompt = AiPrompts.analyzeInstructions(listOf("say \"hi\""))

        assertTrue(prompt, prompt.contains("""["say \"hi\""]"""))
    }
}
