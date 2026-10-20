package com.unchunks.echomark.ui.chat

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.repository.AiSetupState

data class ChatUiState(
    /** 会話のタイトル。まだ会話が作られていなければ null(「新しいチャット」と表示)。 */
    val title: String? = null,
    /** 会話が作成済みか(リネーム・削除はこのときだけできる)。 */
    val hasConversation: Boolean = false,
    /** 既存の会話のメッセージを読み込み中。新規会話では最初から false。 */
    val isLoading: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    /** 引用カード表示用の bookmarkId -> ブックマーク。削除済みブックマークは含まれない。 */
    val referencedBookmarks: Map<Long, Bookmark> = emptyMap(),
    /** 「このブックマークについて質問」の対象。通常の会話では null。 */
    val aboutBookmark: Bookmark? = null,
    /** 空の会話で出す質問の例。 */
    val suggestions: List<String> = emptyList(),
    val isSending: Boolean = false,
    /**
     * 生成中の回答(先頭からの全文)。生成中でなければ null。
     * 送信直後で最初の文字がまだ届いていない間は空文字。完了すると [messages] 側に保存済みの回答が現れる。
     */
    val streamingText: String? = null,
    /** 文脈に使うブックマークが決まったら、その件数。関連ブックマークを探している間は null。 */
    val pendingReferenceCount: Int? = null,
    val error: ChatError? = null,
    val aiSetup: AiSetupState = AiSetupState.READY
) {
    /** 何もメッセージが無い新しい会話(挨拶と質問の例を出す)。 */
    val isEmptyConversation: Boolean
        get() = !isLoading && messages.isEmpty() && !isSending && error == null
}

/**
 * 送信の失敗。
 * @param message 画面に出す日本語
 * @param failedMessage 再試行で送り直す質問
 * @param needsAiSettings API キー未設定・モデル未取り込みなど、AI 設定を見直せば直る失敗
 */
data class ChatError(
    val message: String,
    val failedMessage: String,
    val needsAiSettings: Boolean = false
)
