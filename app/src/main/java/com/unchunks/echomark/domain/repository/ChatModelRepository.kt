package com.unchunks.echomark.domain.repository

import com.unchunks.components.llm.ApiProvider
import kotlinx.coroutines.flow.Flow

/** チャット画面で選べる AI の1つ。 */
sealed interface ChatModelOption {
    /**
     * 取り込んだ端末内モデル。
     * @property fileName 取り込み元のファイル名(表示用)
     */
    data class Local(val modelId: String, val fileName: String) : ChatModelOption

    /**
     * キーを設定した提供元のクラウドのモデル。
     * @property label 画面に出す短い名前
     */
    data class Cloud(val provider: ApiProvider, val modelId: String, val label: String) : ChatModelOption
}

/**
 * チャット画面の AI の選択肢。
 * @property options 選べる AI(端末内、続けて提供元ごとのクラウドの順)
 * @property selected 次の質問に使う AI。[options] に無い(モデル未取り込み・キー未設定)なら null
 */
data class ChatModelChoices(
    val options: List<ChatModelOption> = emptyList(),
    val selected: ChatModelOption? = null
)

/**
 * チャットに使う AI の選択肢と、その選択。「チャット」の用途の設定(AI 設定の画面と同じもの)を読み書きするため、
 * 選んだ AI は新しい会話にも引き継がれ、AI 設定の画面にも反映される。
 */
interface ChatModelRepository {
    val choices: Flow<ChatModelChoices>

    /** 次の質問から [option] を使う */
    suspend fun select(option: ChatModelOption)
}
