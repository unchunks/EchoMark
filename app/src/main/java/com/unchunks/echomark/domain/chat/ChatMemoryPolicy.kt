package com.unchunks.echomark.domain.chat

import com.unchunks.echomark.domain.model.ChatMessage

/**
 * 会話の記憶(古い発言の要約)に、どの発言をいつ含めるかの決まり。Android に依存しない純粋な処理で、単体テストできる。
 *
 * 端末内 AI の文脈には上限があり、プロンプトに直接入れられる発言は直近の数件([RagSupport.HISTORY_LIMIT])だけのため、
 * それより前の発言は要約して入れる。要約の生成は遅い端末で数分かかるため、発言ごとではなく、ある程度たまってからまとめて行う。
 */
object ChatMemoryPolicy {
    /** そのままプロンプトに入れる発言の数。これより前の発言を要約する */
    private const val KEEP_RECENT = RagSupport.HISTORY_LIMIT

    /** 文脈に余裕が無いときに、そのまま残す発言の数 */
    private const val KEEP_RECENT_WHEN_TIGHT = 2

    /** これだけたまってから要約する */
    private const val MIN_BATCH = 4

    /** 文脈に余裕が無いときは、この数から要約する(1往復) */
    private const val MIN_BATCH_WHEN_TIGHT = 2

    /** 1回の要約に含める発言の数の上限。たまっている分は次の回に回す */
    private const val MAX_BATCH = 8

    /** 要約に含める発言を選ぶのに読む、まだ要約していない発言の数 */
    const val FETCH_LIMIT = MAX_BATCH + KEEP_RECENT

    /**
     * 次に要約する発言。足りなければ空。
     * @param uncovered まだ要約に含めていない発言(古い順。[FETCH_LIMIT] 件まで)
     * @param tight 文脈に余裕が無いか([com.unchunks.echomark.domain.provider.ChatContextUsage.isTight])
     */
    fun nextBatch(uncovered: List<ChatMessage>, tight: Boolean): List<ChatMessage> {
        val older = uncovered.dropLast(if (tight) KEEP_RECENT_WHEN_TIGHT else KEEP_RECENT)
        return if (older.size < (if (tight) MIN_BATCH_WHEN_TIGHT else MIN_BATCH)) emptyList() else older.take(MAX_BATCH)
    }

    /** [history] のうち、要約に含めていない発言。[coveredUntil] は要約に含めた最後の発言の日時。 */
    fun notCovered(history: List<ChatMessage>, coveredUntil: Long?): List<ChatMessage> =
        if (coveredUntil == null) history else history.filter { it.createdAt > coveredUntil }
}
