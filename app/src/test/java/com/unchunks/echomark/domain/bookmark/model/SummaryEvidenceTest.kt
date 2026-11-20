package com.unchunks.echomark.domain.bookmark.model

import com.unchunks.echomark.domain.model.SummaryFormat
import com.unchunks.echomark.domain.model.SummaryLanguage
import com.unchunks.echomark.domain.model.SummaryQuality
import com.unchunks.echomark.domain.model.SummaryStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 要約の根拠の探し方(#18)と、要約の出来の確かめ方(#25)。 */
class SummaryEvidenceTest {

    private val article = "Jetpack Compose は宣言的に UI を組み立てるツールキットです。" +
        "状態が変わると関係する部分だけが再コンポーズされます。" +
        "不安定な型や毎回生成されるラムダがあると、不要な再コンポーズが起きます。" +
        "この記事では、remember と derivedStateOf の使い分けや、Lazy リストの key 指定など、計測しながら改善する手順を紹介します。" +
        "まずは Layout Inspector で再コンポーズ回数を確認しましょう。" +
        "Lazy リストでは key を指定すると、並べ替えのときに状態が正しく引き継がれます。" +
        "derivedStateOf は、計算結果が変わったときだけ再コンポーズさせたい場面で役に立ちます。" +
        "ビルドの速度を上げる設定についても、最後に簡単に触れます。"

    // ---- 根拠 ----

    @Test
    fun 要約の文に近い本文の一節を根拠として返す() {
        val report = EvidenceFinder.find(
            "不安定な型やラムダがあると不要な再コンポーズが起きる。Layout Inspector で再コンポーズ回数を確認する。",
            article
        )

        assertTrue(report.comparable)
        assertEquals(2, report.items.size)
        assertTrue(report.items[0].quote!!.contains("不要な再コンポーズが起きます"))
        assertTrue(report.items[1].quote!!.contains("Layout Inspector"))
        assertEquals(0, report.unsupportedCount)
    }

    @Test
    fun 本文に無いことを書いた文は根拠なし() {
        val report = EvidenceFinder.find(
            "Compose は 2030 年に廃止される予定で、移行にはデータベースの全面的な書き換えが必要になる。",
            article
        )

        assertTrue(report.comparable)
        assertNull(report.items.single().quote)
        assertEquals(1, report.unsupportedCount)
    }

    @Test
    fun 箇条書きの中黒は除いて文ごとに探す() {
        val report = EvidenceFinder.find("・状態が変わると関係する部分だけが再コンポーズされる\n・remember と derivedStateOf の使い分けを紹介する", article)

        assertEquals(
            listOf("状態が変わると関係する部分だけが再コンポーズされる", "remember と derivedStateOf の使い分けを紹介する"),
            report.items.map { it.sentence }
        )
        assertTrue(report.items.all { it.isSupported })
    }

    @Test
    fun 根拠の一節は長ければ切る() {
        val long = "あ".repeat(400) + "。"
        val report = EvidenceFinder.find("あ".repeat(60), long)

        assertEquals(EvidenceFinder.MAX_QUOTE_CHARS, report.items.single().quote!!.length)
        assertTrue(report.items.single().quote!!.endsWith("…"))
    }

    @Test
    fun 言語が違う要約と本文は突き合わせない() {
        val english = EvidenceFinder.find("Compose recomposes only the parts whose state has changed.", article)
        val noSource = EvidenceFinder.find("要約の文です。ここに内容があります。", "")

        assertFalse(english.comparable)
        assertTrue(english.items.isEmpty())
        assertFalse(noSource.comparable)
    }

    @Test
    fun 英語どうしは突き合わせられる() {
        val body = "Compose recomposes only the parts of the UI whose state has changed. Unstable parameters cause unnecessary recomposition."

        val report = EvidenceFinder.find("Unstable parameters cause unnecessary recomposition.", body)

        assertTrue(report.comparable)
        assertNotNull(report.items.single().quote)
    }

    @Test
    fun 短すぎる文は数えない() {
        val report = EvidenceFinder.find("はい。状態が変わると関係する部分だけが再コンポーズされる。", article)

        assertEquals(1, report.items.size)
    }

    // ---- 出来の確かめ ----

    private val style = SummaryStyle()
    private val good = "状態が変わった部分だけが再コンポーズされること、不安定な型を避けること、計測して改善する手順を紹介した記事。"

    private fun check(summary: String, source: String = article, style: SummaryStyle = this.style, maxChars: Int = 100) =
        SummaryQuality.check(summary, source, style, maxChars)

    @Test
    fun 普通の要約は問題なし() {
        assertEquals(emptyList<SummaryProblem>(), check(good))
    }

    @Test
    fun 空か短すぎる要約() {
        assertEquals(listOf(SummaryProblem.EMPTY), check(""))
        assertEquals(listOf(SummaryProblem.EMPTY), check("記事。"))
        assertEquals(listOf(SummaryProblem.EMPTY), check("Short.", style = SummaryStyle(language = SummaryLanguage.ENGLISH)))
    }

    @Test
    fun 要約を断る文() {
        assertTrue(SummaryProblem.REFUSED in check("申し訳ありませんが、この内容は要約できません。"))
        assertTrue(SummaryProblem.REFUSED in check("I'm sorry, but I cannot summarize this text.", style = SummaryStyle(language = SummaryLanguage.ENGLISH)))
    }

    @Test
    fun 指定と違う言語() {
        assertTrue(SummaryProblem.WRONG_LANGUAGE in check("Compose recomposes only the parts whose state has changed, and unstable types should be avoided."))
        assertFalse(
            SummaryProblem.WRONG_LANGUAGE in check(
                "Compose recomposes only the parts whose state has changed, and unstable types should be avoided.",
                style = SummaryStyle(language = SummaryLanguage.ENGLISH)
            )
        )
        // かなの無い漢字だけの文は中国語。日本語を指定していれば違う言語
        assertTrue(SummaryProblem.WRONG_LANGUAGE in check("状态改变时只重新组合相关部分,避免不稳定的类型,测量并改进。"))
        assertFalse(
            SummaryProblem.WRONG_LANGUAGE in check(
                "状态改变时只重新组合相关部分,避免不稳定的类型,测量并改进。",
                style = SummaryStyle(language = SummaryLanguage.CHINESE)
            )
        )
        // 英単語やカタカナの技術用語が混ざった日本語は通す
        assertFalse(SummaryProblem.WRONG_LANGUAGE in check("remember と derivedStateOf の使い分けで、Lazy リストの key を指定する方法の解説記事。"))
    }

    @Test
    fun 長すぎる要約は上限の1_6倍から() {
        val long = "状態が変わった部分だけを再コンポーズする仕組みと、計測して改善する手順を説明する。".repeat(8)

        assertTrue(SummaryProblem.TOO_LONG in check(long, maxChars = 100))
        assertFalse(SummaryProblem.TOO_LONG in check(good, maxChars = 100))
        // 箇条書きは上限が大きい(書き方に合わせた上限を渡す)
        assertFalse(SummaryProblem.TOO_LONG in check(good, maxChars = 150, style = SummaryStyle(format = SummaryFormat.BULLETS)))
    }

    @Test
    fun 英語の長さは語数で見る() {
        val style = SummaryStyle(language = SummaryLanguage.ENGLISH)
        val words = (1..120).joinToString(" ") { "word$it" }

        // 100 文字 ≒ 60 語。1.6 倍の 96 語を超えると長すぎる
        assertTrue(SummaryProblem.TOO_LONG in check(words, style = style, maxChars = 100))
        assertFalse(SummaryProblem.TOO_LONG in check((1..80).joinToString(" ") { "word$it" }, style = style, maxChars = 100))
    }

    @Test
    fun 同じ言葉の繰り返し() {
        val loop = "再コンポーズを減らすコツを紹介します。".repeat(3) + "以上です。"

        assertTrue(SummaryProblem.REPEATED in check(loop))
        assertFalse(SummaryProblem.REPEATED in check(good))
    }

    @Test
    fun 本文をそのまま写した要約() {
        val source = article + "追加の本文が続きます。".repeat(10)
        val copied = "状態が変わると関係する部分だけが再コンポーズされます。不安定な型や毎回生成されるラムダがあると、不要な再コンポーズが起きます。"

        assertTrue(SummaryProblem.COPIED in check(copied, source, maxChars = 200))
        assertFalse(SummaryProblem.COPIED in check(good, source))
        // 本文が短いときは、要約が本文と同じでもよい(短いメモ)
        assertFalse(SummaryProblem.COPIED in check(copied, copied))
    }

    @Test
    fun 本文に根拠の無い要約() {
        val invented = "この記事は料理の作り方を解説している。材料は鶏肉と野菜で、煮込み時間は 2 時間である。仕上げに特製のソースをかける。"

        assertTrue(SummaryProblem.UNSUPPORTED in check(invented))
        assertFalse(SummaryProblem.UNSUPPORTED in check(good))
        // 本文が短い・無いとき(画像やファイルだけの保存)は確かめない
        assertFalse(SummaryProblem.UNSUPPORTED in check(invented, source = "短い本文"))
        assertFalse(SummaryProblem.UNSUPPORTED in check(invented, source = ""))
    }

    @Test
    fun 直し方は問題ごとの文をつなげる() {
        val feedback = SummaryQuality.feedback(listOf(SummaryProblem.REPEATED, SummaryProblem.TOO_LONG))

        assertEquals(SummaryProblem.REPEATED.hint + SummaryProblem.TOO_LONG.hint, feedback)
        assertEquals("", SummaryQuality.feedback(emptyList()))
    }
}
