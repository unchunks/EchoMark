package com.unchunks.echomark.domain.bookmark.model

/** ブックマークに対する AI 処理(要約・タグ・埋め込み)の進行状態。 */
enum class AiStatus {
    /** 未処理(キュー待ち) */
    PENDING,
    /** 処理中 */
    PROCESSING,
    /** 完了 */
    DONE,
    /** 失敗(リトライ上限到達) */
    FAILED,
    /** ローカル LLM のモデル未取得のため待機中。ダウンロード完了後に再実行する */
    WAITING_MODEL
}
