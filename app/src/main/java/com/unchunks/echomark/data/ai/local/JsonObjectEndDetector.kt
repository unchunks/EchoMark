package com.unchunks.echomark.data.ai.local

/**
 * 生成中の出力を増分ごとに受け取り、最初の JSON オブジェクトが閉じた位置を見つける。
 * 解析の生成で、JSON を出し終えた後の余計な続き(小型モデルが同じ文を繰り返すなど)を待たずに打ち切るために使う。
 * 文字列リテラル内の括弧・エスケープされた引用符は数えない。オブジェクトの外の引用符(前置きの文章など)は無視する。
 * スレッドセーフではない(1つの生成の出力を順番に渡す)。
 */
internal class JsonObjectEndDetector {

    /** 最初のオブジェクトの閉じ括弧の直後の位置(これまでに受け取った文字列をつないだ全体での位置)。未完成なら -1。 */
    var endIndex: Int = -1
        private set

    private var length = 0
    private var depth = 0
    private var inString = false
    private var escaped = false

    /** [chunk] を前回の続きとして読む。オブジェクトが閉じたら true を返す(以降は読まずに true を返す)。 */
    fun feed(chunk: String): Boolean {
        if (endIndex >= 0) return true
        for ((i, c) in chunk.withIndex()) {
            when {
                escaped -> escaped = false
                inString -> when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                c == '"' -> if (depth > 0) inString = true
                c == '{' -> depth++
                c == '}' -> if (depth > 0 && --depth == 0) {
                    endIndex = length + i + 1
                    return true
                }
            }
        }
        length += chunk.length
        return false
    }
}
