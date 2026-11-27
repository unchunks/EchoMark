package com.unchunks.echomark.domain.help

import com.unchunks.components.llm.ApiProvider
import com.unchunks.echomark.domain.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * アプリの使い方の質問に AI で答える。AI の実行場所(端末内・クラウド API)とモデルはチャットの用途の設定に従う
 * (使い方のためだけに AI を選ばせない)。
 */
interface AppHelpAssistant {
    /**
     * いま回答に使うクラウド API の提供元。端末内 AI で答えるなら null。
     * 画面の説明(質問を端末の外に送るか)と、AI に渡す案内の量([AppGuideRetriever.CLOUD_LIMIT])を決めるのに使う。
     */
    val cloudProvider: Flow<ApiProvider?> get() = flowOf(null)

    /**
     * [question] に [guide](質問に近い使い方の案内)を根拠に答え、回答を増分で流す。
     * @param actions AI に示す操作。AI は回答の最後に「操作: 名前」と書く([AppActionCatalog.suggest] で取り出す)
     * @param history 直近の会話履歴(古い順。今回の [question] は含まない)
     * @throws com.unchunks.echomark.domain.provider.ModelNotAvailableException 端末内モデルが未取り込みのとき
     * @throws com.unchunks.components.llm.LlmException クラウド API のキーが未設定・呼び出しに失敗したとき
     */
    fun answer(
        question: String,
        guide: List<GuideSection>,
        actions: List<AppActionSpec>,
        history: List<ChatMessage>
    ): Flow<String>
}

/** [AppAction] を実行する(画面を開く操作は画面側で行うので、ここでは扱わない)。 */
interface AppActionRunner {
    /**
     * [action] を実行し、結果を利用者向けの一文で返す(「テーマをダークにしました」など)。
     * 失敗したら例外を投げる(呼び出し側で利用者向けの文にする)。
     */
    suspend fun run(action: AppAction): String
}
