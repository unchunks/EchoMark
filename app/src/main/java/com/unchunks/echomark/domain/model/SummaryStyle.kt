package com.unchunks.echomark.domain.model

import kotlin.math.roundToInt

/**
 * 要約を書かせる言語(#12)。本文の言語によらず、選んだ言語で書かせる。
 * 指示文そのものは日本語のまま、出力する言語だけをこの値で指定する。タグ・カテゴリはこの設定の対象外(今までどおり)。
 *
 * @property label 設定画面での呼び名(その言語の表記)
 * @property promptName 指示文で出力の言語を伝える呼び名
 * @property charsPerLimitChar 要約の長さの上限(日本語の文字数で決めている)に対する、その言語の文字数の比。
 *   端末内 AI の出力の長さの上限(JSON スキーマの maxLength)を、その言語に合わせて広げるために使う
 */
enum class SummaryLanguage(val label: String, val promptName: String, val charsPerLimitChar: Int) {
    JAPANESE("日本語", "日本語", 1),
    ENGLISH("English", "英語", ENGLISH_CHARS_PER_JAPANESE_CHAR),
    CHINESE("中文(简体)", "中国語(簡体字)", 1),
    KOREAN("한국어", "韓国語", 1);

    /**
     * 長さの上限の言い方。単語で数える英語は、日本語の [maxChars] 文字に当たる語数にする(100 文字 ≒ 60 語)。
     * 例: 日本語は「100文字以内の日本語」、英語は「60語以内の英語」。
     */
    fun limitPhrase(maxChars: Int): String = when (this) {
        ENGLISH -> "${(maxChars * ENGLISH_WORDS_PER_JAPANESE_CHAR).roundToInt().coerceAtLeast(1)}語以内の$promptName"
        else -> "${maxChars}文字以内の$promptName"
    }

    companion object {
        /** 設定を読んだときの既定 */
        val DEFAULT = JAPANESE
    }
}

private const val ENGLISH_WORDS_PER_JAPANESE_CHAR = 0.6

/** 英語は 1 語 ≒ 6〜7 文字。日本語 1 文字に当たる語数(0.6)から、余裕を見て 4 倍の文字数まで許す */
private const val ENGLISH_CHARS_PER_JAPANESE_CHAR = 4

/**
 * 要約の書き方(#16)。保存する要約のテキストの形を変える。
 * @property label 設定画面での呼び名
 * @property description 設定画面での説明
 */
enum class SummaryFormat(val label: String, val description: String) {
    /** 短い文章(今までの要約) */
    PARAGRAPH("文章", "短い文章でまとめます"),

    /** 要点を「・」で始まる行にして並べる */
    BULLETS("箇条書き", "要点を 3〜5 行の箇条書きでまとめます"),

    /** 1 文だけ */
    ONE_LINE("1 行", "1 文だけで言い切ります");

    companion object {
        val DEFAULT = PARAGRAPH
    }
}

/**
 * 要約の言語と書き方。設定で選ぶ。既定は今までどおり(日本語の文章)。
 * 要約を作るとき([com.unchunks.echomark.domain.model.AnalysisInput.style])に渡し、指示と出力の長さの上限を決める。
 */
data class SummaryStyle(
    val language: SummaryLanguage = SummaryLanguage.DEFAULT,
    val format: SummaryFormat = SummaryFormat.DEFAULT
) {
    /**
     * 種類・長さから決めた要約の長さ([baseMaxChars]。日本語の文字数)を、書き方に合わせて直す。
     * 箇条書きは行の分だけ長く、1 行は短く保つ。
     */
    fun maxChars(baseMaxChars: Int): Int = when (format) {
        SummaryFormat.PARAGRAPH -> baseMaxChars
        SummaryFormat.BULLETS -> baseMaxChars * 3 / 2
        SummaryFormat.ONE_LINE -> minOf(baseMaxChars, ONE_LINE_MAX_CHARS)
    }

    /** [maxChars] を、言語に合わせた出力の文字数の上限(端末内 AI のスキーマ用)にする。 */
    fun schemaChars(maxChars: Int): Int = maxChars * language.charsPerLimitChar

    companion object {
        /** 1 行の要約の長さの上限(日本語の文字数) */
        const val ONE_LINE_MAX_CHARS = 60
    }
}
