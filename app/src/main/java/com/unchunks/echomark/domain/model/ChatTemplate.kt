package com.unchunks.echomark.domain.model

/**
 * チャットの入力欄に入れる定型文。
 * @property title 一覧に出す名前
 * @property text 入力欄に入れる文。続きを打てるよう、話題は後ろに書き足す形にする
 */
data class ChatTemplate(val id: String, val title: String, val text: String)
