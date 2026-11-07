package com.unchunks.echomark.domain.chat

/**
 * チャットの1回の質問に、会話履歴と資料をどれだけ入れるか。AI の文脈の長さで決まる。
 * @property historyMessages 入れる会話履歴の発言の数
 * @property maxSources 入れる資料(ブックマーク)の数
 * @property maxSourcesWithAttachment 添付があるときに入れる資料の数(残りは添付の本文に使う)
 * @property snippetChars 資料1件の本文の文字数
 * @property passageItems 本文の「関連する部分」を添える、上位の資料の数
 * @property passageChars 「関連する部分」の文字数
 * @property pinnedChars 質問の対象として固定した資料の文字数
 * @property attachmentChars 添付の本文の文字数
 */
data class ChatContextLimits(
    val historyMessages: Int,
    val maxSources: Int,
    val maxSourcesWithAttachment: Int,
    val snippetChars: Int,
    val passageItems: Int,
    val passageChars: Int,
    val pinnedChars: Int,
    val attachmentChars: Int
) {
    companion object {
        /** 端末内 AI。プロンプト全体の予算(LocalChatPrompt)に収まる量 */
        val COMPACT = ChatContextLimits(
            historyMessages = RagSupport.HISTORY_LIMIT,
            maxSources = RagSupport.SEARCH_LIMIT,
            maxSourcesWithAttachment = 3,
            snippetChars = RagSupport.MAX_SNIPPET_CHARS,
            passageItems = 3,
            passageChars = RagSupport.PASSAGE_CHARS,
            pinnedChars = 1_200,
            attachmentChars = 800
        )

        /** クラウド API に渡す会話履歴の発言の数の上限 */
        const val MAX_CLOUD_HISTORY_MESSAGES = 40

        /** クラウド API の入力のうち、発言1件に見込む文字数(履歴に入力の半分ほどを使う) */
        private const val INPUT_CHARS_PER_HISTORY_MESSAGE = 2_500

        /**
         * クラウド API。文脈に余裕があるため、資料を多く長く入れ、履歴も長く残す。
         * @param inputChars そのモデルに1回で渡せる文字数の目安。文脈長が分からないモデルでは小さい値になる
         */
        fun forCloud(inputChars: Int): ChatContextLimits = ChatContextLimits(
            historyMessages = (inputChars / INPUT_CHARS_PER_HISTORY_MESSAGE)
                .coerceIn(RagSupport.HISTORY_LIMIT, MAX_CLOUD_HISTORY_MESSAGES),
            maxSources = 8,
            maxSourcesWithAttachment = 8,
            snippetChars = 1_000,
            passageItems = 8,
            passageChars = 800,
            pinnedChars = 6_000,
            attachmentChars = 3_000
        )
    }
}
