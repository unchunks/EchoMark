package com.unchunks.echomark.domain.model

/**
 * チャットの質問に添付したファイルの内容。この質問への回答の根拠にだけ使い、会話には保存しない。
 * @property name ファイル名(表示と文脈の見出しに使う)
 * @property text ファイルから取り出した本文(取り出せなければ空にしない。呼び出し側で作る前に確かめる)
 */
data class ChatAttachmentContext(
    val name: String,
    val text: String
)

/** 添付を質問に使えないことを表す。[message] は画面にそのまま出せる文言。 */
class ChatAttachmentException(message: String) : Exception(message)
