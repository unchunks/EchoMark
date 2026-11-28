package com.unchunks.echomark.ui.components

import com.unchunks.echomark.domain.bookmark.model.ProcessingFailureReason
import com.unchunks.echomark.domain.repository.CLOUD_AI_ENABLED

/*
 * 処理の失敗の理由(ProcessingFailureReason)の言い方と、次の行動(#68)。
 */

/** 失敗の理由を一文で。何が起きたかと、どうすればよいかを伝える。 */
fun ProcessingFailureReason.message(): String = when (this) {
    ProcessingFailureReason.MODEL_NOT_AVAILABLE -> "端末内 AI のモデルが取り込まれていません。AI 設定でモデルを取り込んでください。"
    ProcessingFailureReason.API_KEY_MISSING -> "クラウド AI の API キーが設定されていません。AI 設定でキーを入力してください。"
    ProcessingFailureReason.API_KEY_INVALID -> "API キーが正しくないか、使えなくなっています。AI 設定でキーを確かめてください。"
    ProcessingFailureReason.BAD_REQUEST -> "選んでいる AI のモデルで受け付けてもらえませんでした。AI 設定でモデルを確かめてください。"
    // クラウドの AI を無効にしている間(CLOUD_AI_ENABLED)は、クラウドへの切り替えを勧めない
    ProcessingFailureReason.NOTHING_TO_ANALYZE -> "このファイルからは要約に使える文字を読み取れませんでした。" +
        if (CLOUD_AI_ENABLED) "クラウド AI に切り替えると要約できることがあります。" else ""
    ProcessingFailureReason.REFUSED -> "AI がこの内容の要約を断りました。" +
        if (CLOUD_AI_ENABLED) "別の AI に切り替えると要約できることがあります。" else "内容を編集してから、もう一度試してください。"
    ProcessingFailureReason.TIMEOUT -> "AI の生成が時間内に終わりませんでした。もう一度試すか、短い内容で試してください。"
    ProcessingFailureReason.ENGINE_UNAVAILABLE -> "この端末では AI のライブラリを使えませんでした。" +
        if (CLOUD_AI_ENABLED) "AI 設定で別の AI に切り替えてください。" else "AI 設定で別のモデルを取り込んでください。"
    ProcessingFailureReason.NETWORK -> "通信できませんでした。接続を確かめて、もう一度試してください。"
    ProcessingFailureReason.RATE_LIMITED -> "AI の利用回数の上限に達しました。しばらく待ってから、もう一度試してください。"
    ProcessingFailureReason.SERVER_ERROR -> "AI のサーバーで障害が起きています。しばらく待ってから、もう一度試してください。"
    ProcessingFailureReason.TOO_MANY_ATTEMPTS -> "何度も中断されたため、処理を止めました。もう一度試してください。"
    ProcessingFailureReason.CANCELLED -> "処理をやめました。「再試行」で、もう一度処理できます。"
    ProcessingFailureReason.UNKNOWN -> "予期しないエラーが起きました。もう一度試してください。"
}

/** 再試行を待っている間の言い方(自動でやり直すことを伝える)。 */
fun ProcessingFailureReason.retryingMessage(): String = when (this) {
    ProcessingFailureReason.NETWORK -> "通信できなかったため、接続が戻ったら自動でやり直します。"
    ProcessingFailureReason.RATE_LIMITED -> "AI の利用回数の上限に達したため、しばらく待ってから自動でやり直します。"
    ProcessingFailureReason.SERVER_ERROR -> "AI のサーバーで障害が起きているため、しばらく待ってから自動でやり直します。"
    else -> "うまくいかなかったため、しばらく待ってから自動でやり直します。"
}

/**
 * 直すには設定の変更が要るか(モデルの取り込み・API キー・AI の切り替え)。true なら「AI 設定を開く」を先に出し、
 * false なら(通信・混雑・一時的な失敗)「再試行」を先に出す。
 */
val ProcessingFailureReason.needsSetup: Boolean
    get() = when (this) {
        ProcessingFailureReason.MODEL_NOT_AVAILABLE,
        ProcessingFailureReason.API_KEY_MISSING,
        ProcessingFailureReason.API_KEY_INVALID,
        ProcessingFailureReason.BAD_REQUEST,
        ProcessingFailureReason.ENGINE_UNAVAILABLE -> true
        // AI を切り替えれば直ることがある。切り替え先のクラウドを無効にしている間は、再試行を先に出す
        ProcessingFailureReason.NOTHING_TO_ANALYZE,
        ProcessingFailureReason.REFUSED -> CLOUD_AI_ENABLED
        ProcessingFailureReason.TIMEOUT,
        ProcessingFailureReason.NETWORK,
        ProcessingFailureReason.RATE_LIMITED,
        ProcessingFailureReason.SERVER_ERROR,
        ProcessingFailureReason.TOO_MANY_ATTEMPTS,
        ProcessingFailureReason.CANCELLED,
        ProcessingFailureReason.UNKNOWN -> false
    }
