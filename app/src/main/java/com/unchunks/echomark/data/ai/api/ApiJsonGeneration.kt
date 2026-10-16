package com.unchunks.echomark.data.ai.api

import com.unchunks.echomark.data.ai.local.JsonObjectEndDetector
import com.unchunks.echomark.domain.provider.LlmException
import kotlinx.coroutines.flow.takeWhile

/**
 * JSON を1つ返させる呼び出し(要約・タグ・部分要約)を、ストリーミングで受けて JSON が閉じた時点で打ち切る。
 * 打ち切ると収集が止まり、HTTP の接続も閉じる(各社のクライアントはキャンセルで接続を閉じる)ため、
 * JSON の後ろの余計な出力(終了までの待ちや、繰り返し)を待たずに済む。
 *
 * - 拒否・安全フィルタ・通信の失敗は、ストリームが [LlmException] で投げる([ApiLlmClient.complete] と同じ型)
 * - JSON が閉じないまま終わったときは、受け取った全文を返す(呼び出し側の解析が代替を作る)
 * - 何も受け取れなければ [LlmException.Unexpected]([ApiLlmClient.complete] の空応答と同じ)
 *
 * @return 最初の JSON オブジェクトが閉じる位置までのテキスト
 */
internal suspend fun ApiLlmClient.completeJson(request: ApiRequest, credentials: ApiCredentials): String {
    val detector = JsonObjectEndDetector()
    val text = StringBuilder()
    stream(request, credentials)
        .takeWhile { chunk ->
            text.append(chunk)
            !detector.feed(chunk)
        }
        .collect { }
    if (text.isBlank()) throw LlmException.Unexpected("応答が空")
    return if (detector.endIndex >= 0) text.substring(0, detector.endIndex) else text.toString()
}
