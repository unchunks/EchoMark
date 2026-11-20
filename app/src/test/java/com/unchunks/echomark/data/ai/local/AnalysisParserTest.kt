package com.unchunks.echomark.data.ai.local

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisParserTest {

    @Test
    fun 正常なJSONを解析する() {
        val result = AnalysisParser.parse(
            """{"summary": "要約です", "tags": ["a", "b"], "category": "技術"}""",
            "元テキスト"
        )
        assertEquals("要約です", result.summary)
        assertEquals(listOf("a", "b"), result.tags)
        assertEquals("技術", result.category)
    }

    @Test
    fun 前後に説明文やコードブロックがあってもJSONを取り出せる() {
        val response = """
            はい、分析結果です。
            ```json
            {"summary": "要約", "tags": ["x"], "category": "ニュース"}
            ```
            以上です。
        """.trimIndent()
        val result = AnalysisParser.parse(response, "元")
        assertEquals("要約", result.summary)
        assertEquals(listOf("x"), result.tags)
        assertEquals("ニュース", result.category)
    }

    @Test
    fun タグは整形され重複除去と最大件数の制限を受ける() {
        val result = AnalysisParser.parse(
            """{"summary": "s", "tags": ["#kotlin", " kotlin ", "", "b", "c", "d", "e", "f"], "category": "c"}""",
            "元"
        )
        assertEquals(listOf("kotlin", "b", "c"), result.tags)
        assertEquals(AnalysisParser.MAX_TAGS, result.tags.size)
    }

    @Test
    fun 大文字小文字と全角半角だけが違うタグは1つにまとめる() {
        val result = AnalysisParser.parse(
            """{"summary": "s", "tags": ["Android", "android", "ＡＮＤＲＯＩＤ", "＃Kotlin"], "category": "c"}""",
            "元"
        )
        assertEquals(listOf("Android", "Kotlin"), result.tags)
    }

    @Test
    fun 要約が空なら入力テキストの先頭で代替する() {
        val source = "  " + "あ".repeat(150)
        val result = AnalysisParser.parse("""{"summary": "", "tags": [], "category": "技術"}""", source)
        assertEquals("あ".repeat(AnalysisParser.FALLBACK_SUMMARY_CHARS), result.summary)
        assertEquals("技術", result.category)
    }

    @Test
    fun カテゴリが空なら未分類になる() {
        val result = AnalysisParser.parse("""{"summary": "s", "tags": ["t"], "category": " "}""", "元")
        assertEquals("未分類", result.category)
    }

    @Test
    fun tagsやcategoryが欠けていても要約は使える() {
        val result = AnalysisParser.parse("""{"summary": "s"}""", "元")
        assertEquals("s", result.summary)
        assertEquals(emptyList<String>(), result.tags)
        assertEquals("未分類", result.category)
    }

    @Test
    fun JSONが無い出力はフォールバックになる() {
        val result = AnalysisParser.parse("すみません、分析できません", "元のテキスト")
        assertEquals("元のテキスト", result.summary)
        assertEquals(emptyList<String>(), result.tags)
        assertEquals("未分類", result.category)
    }

    @Test
    fun 壊れたJSONはフォールバックになる() {
        val result = AnalysisParser.parse("""{"summary": "途中で切れ""", "元のテキスト")
        assertEquals("元のテキスト", result.summary)
        assertEquals("未分類", result.category)
    }

    @Test
    fun 空文字の出力はフォールバックになる() {
        val result = AnalysisParser.parse("", "元")
        assertEquals("元", result.summary)
    }

    @Test
    fun 出力形式の例を写したタグとカテゴリは使わない() {
        val result = AnalysisParser.parse(
            """{"summary": "s", "tags": ["タグ1", "タグ2", "Kotlin"], "category": "カテゴリ名1つ"}""",
            "元"
        )
        assertEquals(listOf("Kotlin"), result.tags)
        assertEquals("未分類", result.category)
    }

    @Test
    fun 短い指示の出力形式の例を写したタグとカテゴリは使わない() {
        val result = AnalysisParser.parse(
            """{"summary": "s", "tags": ["語", "語", "Kotlin"], "category": "分類"}""",
            "元"
        )
        assertEquals(listOf("Kotlin"), result.tags)
        assertEquals("未分類", result.category)
    }

    @Test
    fun 保存内容に出てこないタグは除く() {
        // 端末内の小さいモデルは、カテゴリの候補の「技術」や指示の例の「照り焼き」を関係ない保存に写す
        val analysis = AnalysisParser.parse(
            """{"summary": "s", "tags": ["技術", "肉じゃが", "照り焼き"], "category": "レシピ"}""",
            "元"
        )
        val kept = AnalysisParser.keepMentionedTags(analysis, "echomark_recipe\n肉じゃがの作り方(4人分)")

        assertEquals(listOf("肉じゃが"), kept.tags)
        assertEquals("レシピ", kept.category)
    }

    @Test
    fun 保存内容に出てくるかは全角半角_大文字小文字を区別しない() {
        val analysis = AnalysisParser.parse("""{"summary": "s", "tags": ["kotlin", "Java"], "category": "技術"}""", "元")

        assertEquals(listOf("kotlin", "Java"), AnalysisParser.keepMentionedTags(analysis, "Ｋｏｔｌｉｎ は JAVA と相互に呼び出せる").tags)
    }

    @Test
    fun どの保存にも付く汎用の語や文になったタグは使わない() {
        val result = AnalysisParser.parse(
            """{"summary": "s", "tags": ["動画", "YouTube", "まとめ", "コルーチンの使い方を説明している。", "Jetpack Compose"], "category": "技術"}""",
            "元"
        )
        assertEquals(listOf("Jetpack Compose"), result.tags)
    }

    @Test
    fun 書類の項目名はタグにしない() {
        // 議事録の「日時:」「出席者:」の見出しをそのままタグにすることがある
        val result = AnalysisParser.parse(
            """{"summary": "s", "tags": ["日時", "出席者", "広告予算"], "category": "会議"}""",
            "元"
        )
        assertEquals(listOf("広告予算"), result.tags)
    }

    @Test
    fun 文字列や1要素に詰められたタグも区切りで分ける() {
        assertEquals(
            listOf("Android", "Kotlin", "Gradle"),
            AnalysisParser.parse("""{"summary": "s", "tags": "Android, Kotlin、Gradle", "category": "技術"}""", "元").tags
        )
        assertEquals(
            listOf("Android", "Kotlin"),
            AnalysisParser.parse("""{"summary": "s", "tags": ["#Android #Kotlin"], "category": "技術"}""", "元").tags
        )
    }

    @Test
    fun 記号を含むタグ名は分けない() {
        assertEquals(
            listOf("CI/CD", "C#"),
            AnalysisParser.parse("""{"summary": "s", "tags": ["CI/CD", "C#"], "category": "技術"}""", "元").tags
        )
    }

    @Test
    fun 長い説明になったカテゴリは未分類にする() {
        val result = AnalysisParser.parse(
            """{"summary": "s", "tags": [], "category": "プログラミングと開発環境に関する技術情報"}""",
            "元"
        )
        assertEquals("未分類", result.category)
    }

    @Test
    fun 部分要約のメモをJSONから取り出す() {
        assertEquals("要点です", AnalysisParser.parseNotes("```json\n{\"notes\": \" 要点です \"}\n```"))
        // 要点が無い部分は空
        assertEquals("", AnalysisParser.parseNotes("""{"notes": ""}"""))
    }

    @Test
    fun 閉じていないJSONやテキストの出力からもメモを取り出す() {
        assertEquals("途中まで", AnalysisParser.parseNotes("""{"notes": "途中まで"""))
        assertEquals("ただの文章", AnalysisParser.parseNotes("```\nただの文章\n```"))
    }
}
