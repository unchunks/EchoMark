package com.unchunks.echomark.data.ai.local

/**
 * 端末内 AI のチャットの出力を見張り、繰り返しに陥ったら・長くなりすぎたら生成を止める合図を出す(純粋な処理で、単体テストできる)。
 *
 * 小型のモデルは同じ行を延々と繰り返すことがあり、そのまま待つと入力と出力の合計の上限まで(数分)生成を続ける。
 * 同じ行(短い行は除く)が [maxLineRepeats] 回目に出てきた時点、または出力が [maxChars] 文字を超えた時点で [stopped] にする。
 * 止めた後に届いた出力は捨てる。
 */
internal class ChatOutputGuard(
    private val maxChars: Int = MAX_OUTPUT_CHARS,
    private val maxLineRepeats: Int = MAX_LINE_REPEATS
) {
    private val output = StringBuilder()

    /** 書き終わった行ごとの出てきた回数 */
    private val lineCounts = HashMap<String, Int>()

    /** 次に調べる、まだ数えていない行の始まり */
    private var lineStart = 0

    var stopped = false
        private set

    /**
     * 届いた出力の増分を受け取り、画面に流してよい部分を返す。止めた後は空文字。
     * 繰り返しを見つけた行は流さない(その前までを返す)。
     */
    fun accept(chunk: String): String {
        if (stopped || chunk.isEmpty()) return ""
        val before = output.length
        output.append(chunk)
        var allowedEnd = output.length
        while (true) {
            val newline = output.indexOf("\n", lineStart)
            if (newline < 0) break
            val line = output.substring(lineStart, newline).trim()
            val start = lineStart
            lineStart = newline + 1
            if (line.length < MIN_REPEAT_LINE_CHARS) continue
            val count = (lineCounts[line] ?: 0) + 1
            lineCounts[line] = count
            if (count >= maxLineRepeats) {
                stopped = true
                allowedEnd = start
                break
            }
        }
        if (!stopped && output.length > maxChars) {
            stopped = true
            allowedEnd = maxChars
        }
        return if (allowedEnd <= before) "" else output.substring(before, allowedEnd)
    }

    companion object {
        /** チャットの回答の文字数の上限。日本語は 1 文字 1 トークン前後のため、上限はおよそ 1,500 トークン */
        const val MAX_OUTPUT_CHARS = 1_500

        /** 同じ行が何回目に出てきたら繰り返しとみなすか */
        const val MAX_LINE_REPEATS = 3

        /** これより短い行(「---」・箇条書きの記号・空行など)は、同じでも繰り返しとみなさない */
        private const val MIN_REPEAT_LINE_CHARS = 8
    }
}
