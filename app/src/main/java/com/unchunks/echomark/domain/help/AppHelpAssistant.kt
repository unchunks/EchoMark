package com.unchunks.echomark.domain.help

import com.unchunks.echomark.domain.model.ChatMessage
import kotlinx.coroutines.flow.Flow

/** アプリの使い方の質問に、端末内 AI で答える。 */
interface AppHelpAssistant {
    /**
     * [question] に [guide](質問に近い使い方の案内)を根拠に答え、回答を増分で流す。
     * 端末内 AI はチャットの用途で選んだモデルを使う(使い方のためだけにモデルを選ばせない)。
     * @param history 直近の会話履歴(古い順。今回の [question] は含まない)
     * @throws com.unchunks.echomark.domain.provider.ModelNotAvailableException 端末内モデルが未取り込みのとき
     */
    fun answer(question: String, guide: List<GuideSection>, history: List<ChatMessage>): Flow<String>
}
