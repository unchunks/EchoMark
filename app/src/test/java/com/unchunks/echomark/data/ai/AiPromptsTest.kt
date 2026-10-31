package com.unchunks.echomark.data.ai

import com.unchunks.echomark.domain.model.CategoryNames
import com.unchunks.echomark.domain.model.CategoryDefinition
import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.bookmark.model.LimitedAccess
import com.unchunks.echomark.domain.bookmark.model.SummaryProfile
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.model.SummaryFormat
import com.unchunks.echomark.domain.model.SummaryLanguage
import com.unchunks.echomark.domain.model.SummaryStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 解析の指示(種類ごとの方針・既存のタグを入れる量と書き方)と入力の組み立て。 */
class AiPromptsTest {

    @Test
    fun チャットは文章で答えさせ_箇条書きは必要なときだけ使わせる() {
        val full = AiPrompts.chatInstructions(emptyList())
        val compact = AiPrompts.chatInstructions(emptyList(), compact = true)

        assertTrue(full, full.contains("段落に分けた自然な文章"))
        assertTrue(full, full.contains("形にした方が読みやすいときだけ Markdown"))
        assertTrue(compact, compact.contains("短い文章で答えます"))
        assertTrue(compact, compact.contains("箇条書きは手順や比較だけ"))
    }

    @Test
    fun 既存のタグを優先順のまま指示に入れて使い回させる() {
        val prompt = AiPrompts.analyzeInstructions(ContentKind.WEB_PAGE, listOf("Android", "読書"))

        assertTrue(prompt, prompt.contains("既存のタグ:\n[\"Android\", \"読書\"]"))
        assertTrue(prompt, prompt.contains("表記を変えずにそのまま使って"))
        assertTrue(prompt, prompt.contains("1〜${AiPrompts.MAX_TAGS}個"))
    }

    @Test
    fun まとめて作るときは要約_タグ_カテゴリを出力させる() {
        val prompt = AiPrompts.analyzeInstructions(ContentKind.WEB_PAGE, emptyList(), summaryMaxChars = 150)

        assertTrue(prompt, prompt.contains("{\"summary\": \"150文字以内の日本語の要約\", \"tags\": [\"タグ1\", \"タグ2\"], \"category\": \"カテゴリ名1つ\"}"))
    }

    @Test
    fun 動画の要約は字幕の話の流れから要点を選び_前置きや宣伝を要点にしない() {
        val prompt = AiPrompts.analyzeInstructions(ContentKind.VIDEO, emptyList())

        assertTrue(prompt, prompt.contains("話の流れ(導入・主な論点・結論"))
        assertTrue(prompt, prompt.contains("冒頭や締めのあいさつ・自己紹介・チャンネルの紹介"))
        assertTrue(prompt, prompt.contains("自動生成の字幕の誤認識"))
    }

    @Test
    fun 要約だけのときはタグとカテゴリの指示を入れない() {
        val prompt = AiPrompts.analyzeInstructions(ContentKind.WEB_PAGE, listOf("Android"), scope = AnalysisScope.SUMMARY)

        assertTrue(prompt, prompt.contains("{\"summary\": "))
        assertTrue(prompt, prompt.contains("要約の方針:"))
        assertFalse(prompt, prompt.contains("tags"))
        assertFalse(prompt, prompt.contains("category"))
        assertFalse(prompt, prompt.contains("既存のタグ"))
    }

    @Test
    fun タグだけのときは要約の指示を入れず_既存のタグは使い回させる() {
        val prompt = AiPrompts.analyzeInstructions(ContentKind.WEB_PAGE, listOf("Android"), scope = AnalysisScope.TAGS)

        assertTrue(prompt, prompt.contains("{\"tags\": [\"タグ1\", \"タグ2\"], \"category\": \"カテゴリ名1つ\"}"))
        assertFalse(prompt, prompt.contains("summary"))
        assertFalse(prompt, prompt.contains("要約の方針:"))
        assertTrue(prompt, prompt.contains("既存のタグ:\n[\"Android\"]"))
    }

    @Test
    fun 要約にはタイトルやチャンネル名や前置きを書かせない() {
        val prompt = AiPrompts.analyzeInstructions(ContentKind.VIDEO, emptyList())

        assertTrue(prompt, prompt.contains("summary には要約の本文だけを書く"))
        assertTrue(prompt, prompt.contains("チャンネル名"))
        // タグだけを作るときは要約のルールを入れない
        assertFalse(
            AiPrompts.analyzeInstructions(ContentKind.VIDEO, emptyList(), scope = AnalysisScope.TAGS)
                .contains("summary には")
        )
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
    fun 本文に出てこない既存のタグは渡さない() {
        // 端末内の小さいモデルは、渡した「技術」「AI」を選挙の記事にもそのまま付けてしまう
        val text = "米中間選挙\n選挙前に物価を下げる政策が打たれ、日本株や為替に影響する"

        assertEquals(
            listOf("選挙", "日本株"),
            AiPrompts.existingTagsMentionedIn(listOf("技術", "選挙", "AI", "日本株", "仕事"), text)
        )
    }

    @Test
    fun 本文に出てくるかは全角半角_大文字小文字を区別せず_1文字のタグは渡さない() {
        val text = "Ｋｏｔｌｉｎ のコルーチンを Android で使う。本の紹介"

        assertEquals(
            listOf("kotlin", "ANDROID"),
            AiPrompts.existingTagsMentionedIn(listOf("kotlin", "本", "ANDROID", "#ios"), text)
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
    fun 端末内向けの短い指示は出力の例を1つ見せ_カテゴリは候補から選ばせる() {
        val prompt = AiPrompts.compactAnalyzeInstructions(ContentKind.MEMO, emptyList(), summaryMaxChars = 150)

        assertTrue(prompt, prompt.contains("""{"summary": "要約", "tags": ["語", "語"], "category": "分類"}"""))
        assertTrue(prompt, prompt.contains("150文字以内"))
        assertTrue(prompt, prompt.contains("本文に出てくる、この内容に固有の語"))
        assertTrue(prompt, prompt.contains("category: 「アイデア」「ToDo」「技術」"))
        assertTrue(prompt, prompt.contains("""出力: {"summary": "鶏もも肉を甘辛いたれで焼く照り焼きの作り方。", "tags": ["照り焼き", "鶏もも肉"], "category": "レシピ"}"""))
        assertFalse(prompt, prompt.contains("既存のタグ"))
        assertFalse(prompt, prompt.contains("[部分 1/3]"))
        // クラウド向けの指示より短い(遅い端末ではプロンプトの読み込みの時間が文字数に比例する)
        assertTrue(prompt.length < AiPrompts.analyzeInstructions(ContentKind.MEMO, emptyList()).length * 2 / 3)
    }

    @Test
    fun 端末内向けの短い指示も既存のタグと部分ごとのメモと出力する項目に合わせる() {
        val withTags = AiPrompts.compactAnalyzeInstructions(ContentKind.WEB_PAGE, listOf("Android"), digest = true)
        assertTrue(withTags, withTags.contains("既存のタグに合うものがあれば表記を変えずに使う: [\"Android\"]"))
        assertTrue(withTags, withTags.contains("[部分 1/3]"))

        val summaryOnly = AiPrompts.compactAnalyzeInstructions(ContentKind.WEB_PAGE, listOf("Android"), scope = AnalysisScope.SUMMARY)
        assertFalse(summaryOnly, summaryOnly.contains("tags"))
        assertFalse(summaryOnly, summaryOnly.contains("category"))
        assertTrue(summaryOnly, summaryOnly.contains("""出力: {"summary": "鶏もも肉を甘辛いたれで焼く照り焼きの作り方。"}"""))

        val tagsOnly = AiPrompts.compactAnalyzeInstructions(ContentKind.WEB_PAGE, emptyList(), scope = AnalysisScope.TAGS)
        assertFalse(tagsOnly, tagsOnly.contains("summary"))
        assertTrue(tagsOnly, tagsOnly.contains("""出力: {"tags": ["照り焼き", "鶏もも肉"], "category": "レシピ"}"""))
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
    // ---- 要約の型(#17) ----

    @Test
    fun 型ごとの方針を種類の方針に足す() {
        fun prompt(profile: SummaryProfile) = AiPrompts.analyzeInstructions(ContentKind.WEB_PAGE, emptyList(), profile = profile)

        assertFalse(prompt(SummaryProfile.GENERAL).contains("これは論文"))
        assertTrue(prompt(SummaryProfile.PAPER).contains("これは論文。研究の目的、手法、主な結果、結論"))
        assertTrue(prompt(SummaryProfile.REFERENCE).contains("これは技術資料・リファレンス"))
        assertTrue(prompt(SummaryProfile.QA).contains("これは質問と回答のページ"))
        assertTrue(prompt(SummaryProfile.SOCIAL_POST).contains("いいね/リポストの数"))
        assertTrue(prompt(SummaryProfile.PRODUCT).contains("これは商品ページ"))
        assertTrue(prompt(SummaryProfile.RECIPE).contains("主な材料と、作り方の流れ"))
        // 種類の方針は残す(型は足すだけ)
        assertTrue(prompt(SummaryProfile.PAPER).contains("記事の主張・結論と、それを支える要点をまとめる。"))
        assertTrue(AiPrompts.analyzeInstructions(ContentKind.MEMO, emptyList(), profile = SummaryProfile.LIST).contains("これは項目を並べたメモ"))
    }

    @Test
    fun 型を渡さなければ今までと同じ指示() {
        ContentKind.entries.forEach { kind ->
            assertEquals(
                AiPrompts.analyzeInstructions(kind, listOf("Android")),
                AiPrompts.analyzeInstructions(kind, listOf("Android"), profile = SummaryProfile.GENERAL)
            )
        }
    }

    @Test
    fun 型に合うカテゴリの候補を先に出す() {
        assertEquals("レシピ", AiPrompts.categoryExamples(ContentKind.WEB_PAGE, SummaryProfile.RECIPE).first())
        assertEquals("論文", AiPrompts.categoryExamples(ContentKind.DOCUMENT, SummaryProfile.PAPER).first())
        // 重ならない
        val examples = AiPrompts.categoryExamples(ContentKind.WEB_PAGE, SummaryProfile.REFERENCE)
        assertEquals(examples.distinct(), examples)
        assertEquals(AiPrompts.categoryExamples(ContentKind.WEB_PAGE), AiPrompts.categoryExamples(ContentKind.WEB_PAGE, SummaryProfile.GENERAL))
        assertTrue(AiPrompts.analyzeInstructions(ContentKind.WEB_PAGE, emptyList(), profile = SummaryProfile.RECIPE).contains("category は「レシピ」"))
    }

    @Test
    fun 端末内向けの短い指示にも型の主役を入れる() {
        val general = AiPrompts.compactAnalyzeInstructions(ContentKind.WEB_PAGE, emptyList())
        val paper = AiPrompts.compactAnalyzeInstructions(ContentKind.WEB_PAGE, emptyList(), profile = SummaryProfile.PAPER)

        assertTrue(general, general.contains("記事の主張と要点をまとめる"))
        assertTrue(paper, paper.contains("論文の目的・手法・主な結果・結論をまとめる"))
        assertFalse(paper, paper.contains("記事の主張と要点をまとめる"))
        // 短さを保つ(型で大きく伸ばさない)
        assertTrue(paper.length < general.length + 100)
    }

    @Test
    fun 分割要約のメモの指示にも型の方針を入れる() {
        val part = AiPrompts.partInstructions(ContentKind.DOCUMENT, 400, SummaryProfile.PAPER)

        assertTrue(part, part.contains("これは論文"))
        assertFalse(AiPrompts.partInstructions(ContentKind.DOCUMENT, 400).contains("これは論文"))
    }

    // ---- 一部のみ取得したページ(#23) ----

    @Test
    fun 一部のみ取得の印があるときの扱いを指示に入れる() {
        val prompt = AiPrompts.analyzeInstructions(ContentKind.WEB_PAGE, emptyList())
        val compact = AiPrompts.compactAnalyzeInstructions(ContentKind.WEB_PAGE, emptyList())

        assertTrue(prompt, prompt.contains("「${LimitedAccess.MARKER}」の行があるときは"))
        assertTrue(compact, compact.contains(LimitedAccess.MARKER))
        // タグだけを作るときは要約の方針を入れない
        assertFalse(AiPrompts.analyzeInstructions(ContentKind.WEB_PAGE, emptyList(), scope = AnalysisScope.TAGS).contains(LimitedAccess.MARKER))
    }
    // ---- 要約の言語と書き方(#12・#16) ----

    @Test
    fun 既定の言語と書き方なら今までと同じ指示() {
        ContentKind.entries.forEach { kind ->
            assertEquals(
                AiPrompts.analyzeInstructions(kind, listOf("Android")),
                AiPrompts.analyzeInstructions(kind, listOf("Android"), style = SummaryStyle())
            )
            assertEquals(
                AiPrompts.compactAnalyzeInstructions(kind, listOf("Android")),
                AiPrompts.compactAnalyzeInstructions(kind, listOf("Android"), style = SummaryStyle())
            )
        }
        val prompt = AiPrompts.analyzeInstructions(ContentKind.WEB_PAGE, emptyList())
        assertTrue(prompt, prompt.contains("- summary は日本語で簡潔に。"))
        assertFalse(prompt, prompt.contains("必ず"))
    }

    @Test
    fun 英語を選ぶと英語で書かせ_本文が日本語でも英語にする() {
        val style = SummaryStyle(language = SummaryLanguage.ENGLISH)

        val prompt = AiPrompts.analyzeInstructions(ContentKind.WEB_PAGE, emptyList(), summaryMaxChars = 100, style = style)

        assertTrue(prompt, prompt.contains("\"summary\": \"60語以内の英語の要約\""))
        assertTrue(prompt, prompt.contains("- summary は英語で簡潔に。"))
        assertTrue(prompt, prompt.contains("本文が別の言語でも、summary は必ず英語で書く"))
        assertFalse(prompt, prompt.contains("日本語"))
    }

    @Test
    fun 箇条書きは3から5行で行頭を中黒にして改行で区切らせる() {
        val style = SummaryStyle(format = SummaryFormat.BULLETS)

        val prompt = AiPrompts.analyzeInstructions(ContentKind.WEB_PAGE, emptyList(), summaryMaxChars = 150, style = style)

        assertTrue(prompt, prompt.contains("150文字以内の日本語の箇条書きの要約(行頭は「・」、行は \\n で区切る)"))
        assertTrue(prompt, prompt.contains("要点を3〜5行の箇条書きにする。各行は短い1文で、行頭を「・」にし"))
    }

    @Test
    fun 一行は1文だけで改行しない() {
        val style = SummaryStyle(format = SummaryFormat.ONE_LINE)

        val prompt = AiPrompts.analyzeInstructions(ContentKind.WEB_PAGE, emptyList(), summaryMaxChars = 60, style = style)

        assertTrue(prompt, prompt.contains("60文字以内の日本語の1文の要約"))
        assertTrue(prompt, prompt.contains("1文だけにする。改行しない。"))
    }

    @Test
    fun タグだけを作るときは要約の言語と書き方を入れない() {
        val prompt = AiPrompts.analyzeInstructions(
            ContentKind.WEB_PAGE, emptyList(), scope = AnalysisScope.TAGS, style = SummaryStyle(language = SummaryLanguage.ENGLISH)
        )

        assertFalse(prompt, prompt.contains("英語"))
        assertFalse(prompt, prompt.contains("summary"))
    }

    @Test
    fun 端末内向けの短い指示にも言語と書き方と例を合わせる() {
        val english = AiPrompts.compactAnalyzeInstructions(
            ContentKind.WEB_PAGE, emptyList(), summaryMaxChars = 100, style = SummaryStyle(language = SummaryLanguage.ENGLISH)
        )
        val bullets = AiPrompts.compactAnalyzeInstructions(
            ContentKind.WEB_PAGE, emptyList(), style = SummaryStyle(format = SummaryFormat.BULLETS)
        )

        assertTrue(english, english.contains("60語以内の英語"))
        // 例の要約も英語にする(日本語のままだと出力が日本語に寄る)
        assertTrue(english, english.contains("How to make teriyaki chicken thighs"))
        assertFalse(english, english.contains("照り焼きの作り方"))
        assertTrue(bullets, bullets.contains("3〜5行の箇条書き(行頭は「・」、行は \\n で区切る)"))
        assertTrue(bullets, bullets.contains("・鶏もも肉を皮目から焼く\\n・しょうゆ"))
    }

    @Test
    fun 全部の言語と書き方で例の要約を持つ() {
        SummaryLanguage.entries.forEach { language ->
            SummaryFormat.entries.forEach { format ->
                val prompt = AiPrompts.compactAnalyzeInstructions(
                    ContentKind.WEB_PAGE, emptyList(), style = SummaryStyle(language, format)
                )
                assertTrue("$language $format", prompt.contains("出力: {\"summary\": \""))
            }
        }
    }

    @Test
    fun 要約の長さの上限は書き方に合わせる() {
        val paragraph = AiPrompts.summaryMaxChars(ContentKind.WEB_PAGE, isLong = true, style = SummaryStyle())

        assertEquals(paragraph, AiPrompts.summaryMaxChars(ContentKind.WEB_PAGE, true))
        assertEquals(paragraph * 3 / 2, AiPrompts.summaryMaxChars(ContentKind.WEB_PAGE, true, SummaryStyle(format = SummaryFormat.BULLETS)))
        assertEquals(
            SummaryStyle.ONE_LINE_MAX_CHARS,
            AiPrompts.summaryMaxChars(ContentKind.VIDEO, true, SummaryStyle(format = SummaryFormat.ONE_LINE))
        )
    }
    // ---- GitHub のリポジトリ・ソースコード(#14・#15) ----

    @Test
    fun リポジトリは目的_機能_技術_使い方を書かせ_スター数やバッジは書かせない() {
        val prompt = AiPrompts.analyzeInstructions(ContentKind.WEB_PAGE, emptyList(), profile = SummaryProfile.REPOSITORY)

        assertTrue(prompt, prompt.contains("これは GitHub のリポジトリ。何のためのプロジェクトか(目的)を先に書き"))
        assertTrue(prompt, prompt.contains("スター数・フォーク数・更新日・バッジ"))
        assertTrue(prompt, prompt.contains("アーカイブ済み"))
        assertTrue(prompt, prompt.contains("「リポジトリ:」「説明:」「言語:」「トピック:」などは GitHub の情報、「README:」より後は"))
        assertTrue(prompt, prompt.contains("category は「技術」"))
    }

    @Test
    fun ソースコードは何をするコードかを書かせ_1行ずつの説明はさせない() {
        val prompt = AiPrompts.analyzeInstructions(ContentKind.WEB_PAGE, emptyList(), profile = SummaryProfile.SOURCE_CODE)

        assertTrue(prompt, prompt.contains("これはソースコード。何をするコードか(目的・主な機能)を先に書き"))
        assertTrue(prompt, prompt.contains("1行ずつ説明しない。import・ライセンス表記"))
        assertTrue(prompt, prompt.contains("保存内容はコードそのもの。先頭に「リポジトリ:」「ファイル:」があれば"))
        // メモに貼ったコードにも使える
        assertTrue(AiPrompts.analyzeInstructions(ContentKind.MEMO, emptyList(), profile = SummaryProfile.SOURCE_CODE).contains("これはソースコード"))
    }

    @Test
    fun 端末内向けの短い指示にもリポジトリとソースコードの主役を入れる() {
        val repo = AiPrompts.compactAnalyzeInstructions(ContentKind.WEB_PAGE, emptyList(), profile = SummaryProfile.REPOSITORY)
        val code = AiPrompts.compactAnalyzeInstructions(ContentKind.MEMO, emptyList(), profile = SummaryProfile.SOURCE_CODE)

        assertTrue(repo, repo.contains("何のためのプロジェクトかと、主な機能・使っている技術・使い方をまとめる"))
        assertTrue(code, code.contains("何をするコードかと、言語・主な関数・使っているライブラリをまとめる"))
        assertFalse(repo, repo.contains("記事の主張と要点をまとめる"))
    }
    // ---- 作り直しの直し方(#25)・翻訳(#24) ----

    @Test
    fun 作り直しのときだけ前回の悪かった点を指示に入れる() {
        val without = AiPrompts.analyzeInstructions(ContentKind.WEB_PAGE, emptyList())
        val with = AiPrompts.analyzeInstructions(ContentKind.WEB_PAGE, emptyList(), feedback = "要約が長すぎました。")
        val compact = AiPrompts.compactAnalyzeInstructions(ContentKind.WEB_PAGE, emptyList(), feedback = "要約が長すぎました。")

        assertFalse(without, without.contains("前回の要約"))
        assertTrue(with, with.contains("- 前回の要約は次の点がよくありませんでした。直して書き直してください: 要約が長すぎました。"))
        assertTrue(compact, compact.contains("- 前回の要約の悪かった点: 要約が長すぎました。 直して書き直す。"))
        // タグだけを作るときは要約の直し方を入れない
        assertFalse(
            AiPrompts.analyzeInstructions(ContentKind.WEB_PAGE, emptyList(), scope = AnalysisScope.TAGS, feedback = "長すぎ").contains("前回")
        )
        // 空白だけなら入れない
        assertFalse(AiPrompts.analyzeInstructions(ContentKind.WEB_PAGE, emptyList(), feedback = "  ").contains("前回の要約"))
    }

    @Test
    fun 翻訳の指示は言語とJSONの形と元の文章() {
        val prompt = AiPrompts.translateInstructions("・要点 A\n・要点 B", SummaryLanguage.CHINESE)

        assertTrue(prompt, prompt.startsWith("次の文章を中国語(簡体字)に翻訳し、JSONのみを出力してください。"))
        assertTrue(prompt, prompt.contains("""{"translation": "翻訳した文章"}"""))
        assertTrue(prompt, prompt.endsWith("翻訳する文章:\n・要点 A\n・要点 B"))
    }


    private val myCategories = listOf(
        CategoryDefinition("旅行", "行き先や宿の情報"),
        CategoryDefinition("読書メモ", "")
    )

    @Test
    fun 自分のカテゴリがあれば説明つきで見せ_その中から選ばせる() {
        val prompt = AiPrompts.analyzeInstructions(ContentKind.WEB_PAGE, emptyList(), categories = myCategories)

        assertTrue(prompt, prompt.contains("選べるカテゴリ:\n- \"旅行\": 行き先や宿の情報\n- \"読書メモ\""))
        assertTrue(prompt, prompt.contains("下の「選べるカテゴリ」から内容に最も合うものを1つ選び"))
        assertTrue(prompt, prompt.contains("どれにも当てはまらないときだけ"))
    }

    @Test
    fun 自分のカテゴリが無ければ今までのカテゴリの指示のまま() {
        val prompt = AiPrompts.analyzeInstructions(ContentKind.WEB_PAGE, emptyList())

        assertFalse(prompt, prompt.contains("選べるカテゴリ"))
        assertTrue(prompt, prompt.contains("のように短い1語。"))
    }

    @Test
    fun 要約だけのときはカテゴリを入れない() {
        val prompt = AiPrompts.analyzeInstructions(
            ContentKind.WEB_PAGE, emptyList(), scope = AnalysisScope.SUMMARY, categories = myCategories
        )

        assertFalse(prompt, prompt.contains("選べるカテゴリ"))
    }

    @Test
    fun 端末内AIには自分のカテゴリを短い説明で見せ_合わないときだけ例から選ばせる() {
        val prompt = AiPrompts.compactAnalyzeInstructions(ContentKind.WEB_PAGE, emptyList(), categories = myCategories)

        assertTrue(prompt, prompt.contains("- category: 次から内容に合うものを1つ選ぶ: 「旅行」(行き先や宿の情報)、「読書メモ」"))
        assertTrue(prompt, prompt.contains("どれにも合わないときだけ、"))
    }

    @Test
    fun プロンプトに入れるカテゴリは数と説明の長さを絞り_名前が長すぎるものは入れない() {
        val many = (1..40).map { CategoryDefinition("カテゴリ$it", "あ".repeat(100)) } +
            CategoryDefinition("あ".repeat(CategoryNames.MAX_NAME_CHARS + 1), "x") +
            CategoryDefinition("  ", "x")

        val api = AiPrompts.categoriesForPrompt(many, AiPrompts.API_MAX_CATEGORIES, AiPrompts.API_MAX_CATEGORY_DESCRIPTION_CHARS)
        val local = AiPrompts.categoriesForPrompt(many, AiPrompts.LOCAL_MAX_CATEGORIES, AiPrompts.LOCAL_MAX_CATEGORY_DESCRIPTION_CHARS)

        assertEquals(AiPrompts.API_MAX_CATEGORIES, api.size)
        assertEquals(AiPrompts.LOCAL_MAX_CATEGORIES, local.size)
        assertTrue(api.all { it.description.length == AiPrompts.API_MAX_CATEGORY_DESCRIPTION_CHARS })
        assertTrue(local.all { it.description.length == AiPrompts.LOCAL_MAX_CATEGORY_DESCRIPTION_CHARS })
        assertEquals(
            listOf("旅行"),
            AiPrompts.categoriesForPrompt(
                listOf(CategoryDefinition("あ".repeat(CategoryNames.MAX_NAME_CHARS + 1)), CategoryDefinition(" 旅行 ")), 5, 10
            ).map { it.name }
        )
    }
}
