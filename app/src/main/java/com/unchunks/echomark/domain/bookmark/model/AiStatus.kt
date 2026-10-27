package com.unchunks.echomark.domain.bookmark.model

/**
 * ブックマークの処理(本文の取得・中身の取り出し・要約・タグ・埋め込み)の進行状態。
 * 段・日時・失敗の理由は [ProcessingInfo] に持つ。DB・バックアップには名前で保存するため、名前を変えない。
 */
enum class AiStatus {
    /** 処理待ち(順番待ち・次の段待ち・再試行待ち)。実行しているワークは無い */
    PENDING,
    /** 処理中。どの段かは [ProcessingInfo.stage] */
    PROCESSING,
    /** 完了 */
    DONE,
    /** 失敗(リトライ上限到達) */
    FAILED,
    /**
     * AI の準備待ち(ローカル LLM のモデル未取り込み、API キー未設定など)。
     * モデル取り込み完了時や設定画面の「再処理」で再実行する
     */
    WAITING_MODEL
}
