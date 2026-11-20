package com.unchunks.echomark.data.podcast

import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.data.ai.LocalLlmProviders
import com.unchunks.echomark.domain.chat.ChatContextLimits
import com.unchunks.echomark.domain.podcast.PodcastFormat
import com.unchunks.echomark.domain.podcast.PodcastSource
import com.unchunks.echomark.domain.podcast.PodcastSpeaker
import com.unchunks.echomark.domain.podcast.ReadingEntry
import com.unchunks.echomark.domain.podcast.ScriptGenerator
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.FakeApiKeyRepository
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeLlmProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** 台本を書く流れ(AI は Fake)。出典の紹介・要約の読み上げへの切り替え・読みの確かめ。 */
class PodcastScriptWriterTest {

    /** 指示の種類ごとに決めた応答を返す AI */
    private class ScriptedLlm(
        var segment: String = """{"lines":[{"speaker":"A","text":"日本橋で発表がありました。"},{"speaker":"B","text":"へえ。"}]}""",
        var readings: String = """{"readings":[{"surface":"日本橋","reading":"にほんばし"},{"surface":"作り話","reading":"つくりばなし"}]}""",
        var limits: ChatContextLimits = ChatContextLimits.COMPACT,
        var segmentFailure: Exception? = null
    ) : LlmProvider by FakeLlmProvider() {
        val prompts = mutableListOf<String>()

        override suspend fun chatContextLimits(): ChatContextLimits = limits

        override suspend fun complete(prompt: String): String {
            prompts += prompt
            return if (prompt.contains("読み間違えやすい語の読み")) readings
            else segmentFailure?.let { throw it } ?: segment
        }
    }

    private fun source(index: Int) = PodcastSource(
        bookmarkId = index.toLong(), index = index, title = "記事$index", kindLabel = "記事", sourceName = "Zenn",
        url = "https://zenn.dev/$index", summary = "要約${index}の一文目。二文目。", excerpt = "本文".repeat(1_000)
    )

    private fun writer(llm: LlmProvider?): PodcastScriptWriter {
        val providers = LocalLlmProviders { llm ?: throw ModelNotAvailableException() }
        return PodcastScriptWriter(LlmProviderResolver(providers, { FakeLlmProvider() }, FakeAppSettingsRepository(LlmBackend.LOCAL), FakeApiKeyRepository()))
    }

    @Test
    fun AIが書いた紹介に_出典の紹介と始まり終わりを付ける() = runBlocking {
        val llm = ScriptedLlm()
        val script = writer(llm).write(listOf(source(1), source(2)), PodcastFormat.DUO)

        assertEquals(ScriptGenerator.AI, script.generator)
        assertEquals("記事1 ほか1件", script.title)
        val texts = script.lines.map { it.text }
        assertTrue(texts.first().startsWith("こんにちは。EchoMark ポッドキャストです。"))
        assertTrue(texts.contains("1件目は、Zennの記事「記事1」です。"))
        assertTrue(texts.contains("2件目は、Zennの記事「記事2」です。"))
        assertEquals(PodcastSpeaker.GUEST, script.lines.first { it.text == "へえ。" }.speaker)
        assertTrue(texts.last() == "ありがとうございました。")
        // 紹介の行は、どのブックマークのものかが分かる
        assertEquals(listOf(1, 1, 1), script.lines.filter { it.sourceIndex == 1 }.map { it.sourceIndex })
        // 台本に無い語(作り話)の読みは使わない。同じ表記は1つにまとめる
        assertEquals(listOf(ReadingEntry("日本橋", "にほんばし")), script.readings)
        // 端末内 AI では本文を短く切って渡す
        assertTrue(llm.prompts.first().contains("本文の抜粋: " + "本文".repeat(250)))
        assertTrue(!llm.prompts.first().contains("本文".repeat(251)))
    }

    @Test
    fun AIを使えなければ要約を読む() = runBlocking {
        val script = writer(null).write(listOf(source(1)), PodcastFormat.SOLO)

        assertEquals(ScriptGenerator.SUMMARY_READ, script.generator)
        assertEquals(
            listOf("EchoMark ポッドキャストです。保存したブックマークを1件紹介します。", "紹介するのは、Zennの記事「記事1」です。", "要約1の一文目。", "二文目。"),
            script.lines.dropLast(1).map { it.text }
        )
        assertTrue(script.lines.all { it.speaker == PodcastSpeaker.HOST })
        assertEquals(emptyList<ReadingEntry>(), script.readings)
    }

    @Test
    fun 形の崩れた紹介はその件だけ要約を読む() = runBlocking {
        val llm = ScriptedLlm(segment = "台本です")
        val script = writer(llm).write(listOf(source(1)), PodcastFormat.SOLO)

        assertEquals(ScriptGenerator.SUMMARY_READ, script.generator)
        assertTrue(script.lines.any { it.text == "要約1の一文目。" })
        // 作り直しを1回させる(最初の1回 + 作り直し1回 + 読み)
        assertEquals(2, llm.prompts.count { it.contains("台本にして") })
        // 要約を読む件でも、AI が使えれば読みは確かめる
        assertEquals(1, llm.prompts.count { it.contains("読み間違えやすい語の読み") })
    }

    @Test
    fun 途中でAIが失敗したら_残りはAIを呼ばずに要約を読む() = runBlocking {
        val llm = ScriptedLlm(segmentFailure = IOException("時間切れ"))
        val script = writer(llm).write(listOf(source(1), source(2), source(3)), PodcastFormat.SOLO)

        assertEquals(ScriptGenerator.SUMMARY_READ, script.generator)
        assertEquals(1, llm.prompts.size)
        assertTrue(script.lines.any { it.text == "要約3の一文目。" })
    }

    @Test
    fun クラウドのAIには長めの本文を渡す() = runBlocking {
        val llm = ScriptedLlm(limits = ChatContextLimits.COMPACT.copy(snippetChars = ChatContextLimits.COMPACT.snippetChars + 1))
        writer(llm).write(listOf(source(1)), PodcastFormat.SOLO)
        assertTrue(llm.prompts.first().contains("本文".repeat(1_000)))
    }
}
