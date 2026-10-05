package com.unchunks.echomark.data.ai

import com.unchunks.echomark.domain.bookmark.model.ContentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 解析の指示(種類ごとの方針・既存のタグを入れる量と書き方)と入力の組み立て。 */
class AiPromptsTest {

    @Test
    fun 既存のタグを優先順のまま指示に入れて使い回させる() {
        val prompt = AiPrompts.analyzeInstructions(ContentKind.WEB_PAGE, listOf("Android", "読書"))

        assertTrue(prompt, prompt.contains("既存のタグ:\n[\"Android\", \"読書\"]"))
        assertTrue(prompt, prompt.contains("表記を変えずにそのまま使って"))
        assertTrue(prompt, prompt.contains("1〜${AiPrompts.MAX_TAGS}個"))
    }

    @Test
    fun 既存のタグが無ければ既存タグの指示を入れない() {
        val prompt = AiPrompts.analyzeInstructions(ContentKind.WEB_PAGE, emptyList())

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
        val prompt = AiPrompts.analyzeInstructions(ContentKind.WEB_PAGE, listOf("say \"hi\""))

        assertTrue(prompt, prompt.contains("""["say \"hi\""]"""))
    }

    @Test
    fun 種類ごとに要約の主役と無視するものを伝える() {
        fun prompt(kind: ContentKind) = AiPrompts.analyzeInstructions(kind, emptyList())

        assertTrue(prompt(ContentKind.WEB_PAGE).contains("記事の主張・結論"))
        assertTrue(prompt(ContentKind.WEB_PAGE).contains("広告・ナビゲーション"))
        val video = prompt(ContentKind.VIDEO)
        assertTrue(video, video.contains("動画で話している内容"))
        assertTrue(video, video.contains("SNS の告知"))
        assertTrue(video, video.contains("「字幕(自動生成):」"))
        assertTrue(prompt(ContentKind.MEMO).contains("言い換えたり膨らませたりせず"))
        assertTrue(prompt(ContentKind.IMAGE).contains("レシート"))
        assertTrue(prompt(ContentKind.DOCUMENT).contains("文書の種類"))
        assertTrue(prompt(ContentKind.AUDIO).contains("結論や決まったこと"))
        // 字幕の見出しの説明は動画だけに入れる(他の種類では無駄な指示になる)
        assertFalse(prompt(ContentKind.IMAGE).contains("字幕"))
        // 取り出したテキストの見出しはどの種類にも説明する
        ContentKind.entries.forEach { assertTrue(it.name, prompt(it).contains("--- 読み取った文字 ---")) }
    }

    @Test
    fun 種類に合うカテゴリの候補を先に出し_出力形式は変えない() {
        val image = AiPrompts.analyzeInstructions(ContentKind.IMAGE, emptyList())

        assertTrue(image, image.contains("category は「写真」「スクショ」「レシート」「資料」「技術」"))
        // AnalysisParser が読む形式(summary / tags / category)のまま
        ContentKind.entries.forEach {
            val prompt = AiPrompts.analyzeInstructions(it, emptyList())
            assertTrue(prompt, prompt.contains("""{"summary": """))
            assertTrue(prompt, prompt.contains(""""tags": ["タグ1", "タグ2"], "category": "カテゴリ名1つ"}"""))
        }
    }

    @Test
    fun 長い動画や文書は要約を長めにする() {
        assertEquals(100, AiPrompts.summaryMaxChars(ContentKind.VIDEO, isLong = false))
        assertEquals(200, AiPrompts.summaryMaxChars(ContentKind.VIDEO, isLong = true))
        assertEquals(200, AiPrompts.summaryMaxChars(ContentKind.DOCUMENT, isLong = true))
        assertEquals(200, AiPrompts.summaryMaxChars(ContentKind.AUDIO, isLong = true))
        assertEquals(150, AiPrompts.summaryMaxChars(ContentKind.WEB_PAGE, isLong = true))
        assertEquals(100, AiPrompts.summaryMaxChars(ContentKind.MEMO, isLong = true))

        val prompt = AiPrompts.analyzeInstructions(ContentKind.VIDEO, emptyList(), summaryMaxChars = 200)
        assertTrue(prompt, prompt.contains("200文字以内の日本語の要約"))
    }

    @Test
    fun 入力はタイトルと本文を分け_本文を上限で切り詰める() {
        val input = AiPrompts.analyzeInput(title = " 題名 ", body = "あいうえお", maxChars = 3)

        assertEquals("タイトル: 題名\n保存内容:\nあいう", input)
    }

    @Test
    fun 本文が無ければその旨を書き_添付があれば伝える() {
        val input = AiPrompts.analyzeInput(title = "", body = " ", maxChars = 100, attachmentLabel = "画像")

        assertEquals(
            "添付: 保存した画像のファイルそのもの。本文と合わせて内容を判断してください。\n保存内容:\n(本文なし)",
            input
        )
    }
}
