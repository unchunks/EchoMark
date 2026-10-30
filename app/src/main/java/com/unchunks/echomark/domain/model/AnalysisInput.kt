package com.unchunks.echomark.domain.model

import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.bookmark.model.SummaryProfile

/**
 * AI に要約・タグ付けさせる入力。
 *
 * @property title ブックマークのタイトル
 * @property text 取り出した本文(Web ページの本文・PDF のテキスト・OCR の結果・文字起こしなど。メモを含む)。空のこともある
 * @property kind 中身の種類。種類ごとに要約の指示を変える
 * @property profile 要約の型(論文・Q&A・レシピなど)。型ごとに要約の方針を足す
 * @property style 要約の言語と書き方(設定。#12・#16)。要約の指示と長さの上限を決める
 * @property attachment 元のファイル(画像・音声など)。クラウド API が直接扱える形式なら、テキストに加えて渡す
 */
data class AnalysisInput(
    val title: String,
    val text: String,
    val kind: ContentKind,
    val attachment: AnalysisAttachment? = null,
    val profile: SummaryProfile = SummaryProfile.GENERAL,
    val style: SummaryStyle = SummaryStyle()
) {
    /** タイトルと本文を1つのテキストにしたもの(空の部分は除く) */
    fun combinedText(): String = listOf(title, text).filter { it.isNotBlank() }.joinToString("\n")
}

/**
 * AI に直接渡せる元のファイル。
 * @property path 端末内の絶対パス
 */
data class AnalysisAttachment(
    val path: String,
    val mimeType: String,
    val sizeBytes: Long
)
