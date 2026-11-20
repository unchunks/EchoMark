package com.unchunks.echomark.domain.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 読み上げ用の整え。読み間違いの起きやすい書き方ごとに、読み上げに渡す文を確かめる。 */
class SpeechNormalizerTest {

    private fun spoken(text: String, dictionary: List<ReadingEntry> = emptyList(), ai: List<ReadingEntry> = emptyList()) =
        SpeechNormalizer.normalize(text, dictionary, ai).plain

    // --- 読めないものを除く ---

    @Test
    fun URLとメールアドレスは読まない() {
        assertEquals("詳しくはこちら。", spoken("詳しくは https://example.com/a?b=1 こちら。"))
        assertEquals("問い合わせはまで。", spoken("問い合わせは info@example.co.jp まで。"))
        assertEquals("公式サイトを見る", spoken("[公式サイト](https://example.com)を見る"))
    }

    @Test
    fun コードとMarkdownの記号は読まない() {
        assertEquals("設定をgradle.propertiesで変える。\n手順は次のとおり。", spoken("設定を`gradle.properties`で変える。\n```kotlin\nval x = 1\n```\n手順は次のとおり。"))
        assertEquals("見出し\n要点1\n要点2", spoken("## 見出し\n- 要点1\n* 要点2"))
        assertEquals("大事なこと", spoken("**大事なこと**"))
        assertEquals("回答です。", spoken("回答です[1][2, 3]。"))
    }

    @Test
    fun 絵文字と飾りの記号は読まない() {
        assertEquals("新機能が出ました!", spoken("🎉新機能が出ました!✨"))
        assertEquals("速報、新しい発表", spoken("【速報】新しい発表★"))
        assertEquals("いいね", spoken("いいね👍🏻"))
    }

    @Test
    fun 全角の英数字はそろえる() {
        assertEquals("エーアイを使う", spoken("ＡＩを使う"))
    }

    // --- 振り仮名・辞書・AI の読み ---

    @Test
    fun 漢字の後ろの振り仮名は読みにする() {
        val text = SpeechNormalizer.normalize("日本橋(にほんばし)に行く")
        assertEquals("にほんばしに行く", text.plain)
        assertEquals(ReadingOrigin.ANNOTATION, text.readings.single().origin)
    }

    @Test
    fun 利用者の辞書は長い表記から当てる() {
        val dictionary = listOf(ReadingEntry("東京", "とうきょう"), ReadingEntry("東京都庁", "とうきょうとちょう"))
        assertEquals("とうきょうとちょうととうきょう", spoken("東京都庁と東京", dictionary))
    }

    @Test
    fun 英字の表記は大文字小文字を区別せず_単語の境目でだけ当てる() {
        val dictionary = listOf(ReadingEntry("Kotlin", "コトリン"), ReadingEntry("AI", "エイアイ"))
        assertEquals("コトリンとコトリン", spoken("kotlinとKOTLIN", dictionary))
        // MAIL の中の AI には当てない
        assertFalse(spoken("MAILを送る", dictionary).contains("エイアイ"))
    }

    @Test
    fun 利用者の辞書はAIの読みとアプリの読みより先に効く() {
        val user = listOf(ReadingEntry("GitHub", "ギットハブさん"))
        val ai = listOf(ReadingEntry("GitHub", "ギッハブ"), ReadingEntry("日本橋", "にっぽんばし"))
        val text = SpeechNormalizer.normalize("GitHubと日本橋", user, ai)
        assertEquals("ギットハブさんとにっぽんばし", text.plain)
        assertEquals(listOf(ReadingOrigin.USER, ReadingOrigin.AI), text.readings.map { it.origin })
    }

    @Test
    fun AIの読みはかなだけのものを使う() {
        val ai = listOf(ReadingEntry("日本橋", "Nihonbashi"))
        assertEquals("日本橋", spoken("日本橋", ai = ai))
    }

    @Test
    fun 読みに置き換えた部分は後の整えで触らない() {
        // 辞書の読みに数字や英字があっても、日付・略語として読み直さない
        val dictionary = listOf(ReadingEntry("新製品", "ABC 2026/1/1"))
        assertEquals("ABC 2026/1/1", spoken("新製品", dictionary))
    }

    // --- 数の書き方 ---

    @Test
    fun 日付() {
        assertEquals("2026年11月20日に公開", spoken("2026/11/20に公開"))
        assertEquals("2026年1月5日", spoken("2026-01-05"))
        assertEquals("2026年3月", spoken("2026/3"))
        assertEquals("11月20日、金曜日から", spoken("11/20(金)から"))
        assertEquals("5月3日、祝日", spoken("5/3(祝)"))
        // 月・日としてありえないものは日付にしない
        assertEquals("13、40", spoken("13/40"))
    }

    @Test
    fun 時刻() {
        assertEquals("10時30分から", spoken("10:30から"))
        assertEquals("9時に始まる", spoken("9:00に始まる"))
        assertEquals("1時2分3秒", spoken("01:02:03"))
        assertEquals("25時から", spoken("25:00から"))
    }

    @Test
    fun 範囲() {
        assertEquals("10から20人", spoken("10〜20人"))
        assertEquals("3日から5日", spoken("3日~5日"))
        assertEquals("1,000円から", spoken("1,000円~"))
        assertEquals("2020から2024年", spoken("2020-2024年"))
    }

    @Test
    fun 通貨と掛け算と版() {
        assertEquals("100ドル", spoken("$100"))
        assertEquals("1.5万円", spoken("¥1.5万"))
        assertEquals("1920かける1080", spoken("1920×1080"))
        assertEquals("3倍速い", spoken("3x速い"))
        assertEquals("バージョン2.1を公開", spoken("v2.1を公開"))
        assertEquals("バージョン3", spoken("Ver.3"))
        assertEquals("ナンバー1", spoken("No.1"))
    }

    @Test
    fun ハイフンとマイナス() {
        assertEquals("ジーピーティー 4", spoken("GPT-4"))
        assertEquals("マイナス5度", spoken("-5°C"))
    }

    @Test
    fun 電話番号と番号は1けたずつ読ませる() {
        val text = SpeechNormalizer.normalize("電話は03-1234-5678、番号は0042です")
        assertTrue(text.pieces.contains(SpeechPiece.Telephone("03-1234-5678")))
        assertTrue(text.pieces.contains(SpeechPiece.Digits("0042")))
        assertEquals("電話は03-1234-5678、番号は0042です", text.plain)
    }

    @Test
    fun 単位() {
        assertEquals("5キロメートル", spoken("5km"))
        assertEquals("8ギガバイトのメモリ", spoken("8GB のメモリ"))
        assertEquals("時速60キロメートル毎時", spoken("時速60km/h"))
        assertEquals("30度", spoken("30℃"))
        assertEquals("100ワットと3メートル", spoken("100Wと3m"))
        assertEquals("5ジー回線", spoken("5G回線"))
    }

    @Test
    fun 数の後ろでなければ単位にしない() {
        assertEquals("ジーのボタン", spoken("Gのボタン"))
        assertEquals("km", spoken("km"))
    }

    // --- 英字 ---

    @Test
    fun 大文字の略語は1文字ずつ読む() {
        assertEquals("エヌエイチケーのニュース", spoken("NHKのニュース"))
        assertEquals("エーピーアイを呼ぶ", spoken("APIsを呼ぶ"))
        assertEquals("エー社とビー社", spoken("A社とB社"))
        assertEquals("エイチティーエムエル", spoken("HTML"))
    }

    @Test
    fun 単語として読む略語と製品名はアプリの読みを使う() {
        assertEquals("ナサとジェイソン", spoken("NASAとJSON"))
        assertEquals("アイフォーンとギットハブ", spoken("iPhoneとGitHub"))
        assertEquals("シープラスプラスで書く", spoken("C++で書く"))
        assertEquals("ワイファイ", spoken("Wi-Fi"))
    }

    @Test
    fun 母音のある長い大文字の語は1文字ずつ読まない() {
        assertEquals("アンドロイド", spoken("ANDROID"))
        assertEquals("TOKYO", spoken("TOKYO"))
    }

    @Test
    fun ドメイン名の点はドットと読む() {
        assertEquals("exampleドットcomの記事", spoken("example.com の記事"))
        assertEquals("小数の3.5はそのまま", spoken("小数の3.5はそのまま"))
    }

    // --- 記号 ---

    @Test
    fun 記号は読む言葉か区切りにする() {
        assertEquals("研究アンド開発", spoken("研究&開発"))
        assertEquals("東京、大阪", spoken("東京/大阪"))
        assertEquals("タイトル、サイト", spoken("タイトル | サイト"))
        assertEquals("準備、公開", spoken("準備→公開"))
        assertEquals("面白い", spoken("面白いwww"))
    }

    @Test
    fun 区切りは重ねない() {
        assertEquals("速報、発表。", spoken("【速報】、発表。"))
        assertFalse(spoken("【A】【B】").contains("、、"))
    }

    @Test
    fun 空の文は空() {
        assertTrue(SpeechNormalizer.normalize("https://example.com 🎉").isBlank())
    }
}
