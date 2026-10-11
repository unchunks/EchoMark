package com.unchunks.echomark.domain.provider

/**
 * 埋め込みモデルに渡すテキストを作る純粋なヘルパー。Android 依存なしで単体テストできる。
 *
 * 埋め込みモデルには読める長さ([EmbeddingModelProfile.maxInputTokens])の上限があり、超えた分は黙って切り捨てられる。
 * 切り捨てが「タイトルや要約の途中」ではなく「本文の後ろ」で起きるよう、タイトル → 要約 → 本文の先頭の順に
 * 並べ、文字数の予算([charBudget])に収まるところまでで切る。
 */
object EmbeddingInputBuilder {

    /** 区切り */
    private const val SEPARATOR = "\n"

    /** タイトルの最大文字数。極端に長いタイトルで要約・本文が押し出されないようにする */
    const val MAX_TITLE_CHARS = 200

    /** モデルが先頭・末尾やタスク用の接頭辞に使うトークン数の見込み(入力の予算から引く) */
    const val RESERVED_TOKENS = 48

    /** 予算の下限(profile の設定ミスで空の入力にならないように) */
    private const val MIN_BUDGET_CHARS = 256

    /**
     * 入力に使える文字数の予算。
     * 文字数とトークン数の比は [EmbeddingModelProfile.charsPerToken](既定 1.0 = 1 文字 1 トークンと見なす保守的な値)。
     * 日本語は 1 文字 ≒ 0.5〜1 トークン程度なので、2K トークンのモデルでも 2,000 文字弱までなら切り捨てられない。
     */
    fun charBudget(profile: EmbeddingModelProfile): Int =
        ((profile.maxInputTokens - RESERVED_TOKENS) * profile.charsPerToken).toInt().coerceAtLeast(MIN_BUDGET_CHARS)

    /**
     * タイトル・要約・本文の先頭を改行でつなぎ、[charBudget] を超えないようにする。
     * 空白だけの項目は含めない。本文は [EmbeddingModelProfile.maxContentChars] までしか含めない。
     */
    fun build(title: String, summary: String?, content: String?, profile: EmbeddingModelProfile): String {
        val budget = charBudget(profile)
        val parts = mutableListOf<String>()
        var remaining = budget

        // limit 文字までを追加する。区切りの分も予算から引く
        fun add(text: String?, limit: Int) {
            if (text.isNullOrBlank()) return
            val separator = if (parts.isEmpty()) 0 else SEPARATOR.length
            val room = minOf(limit, remaining - separator)
            if (room <= 0) return
            val piece = text.takeSafely(room)
            if (piece.isBlank()) return
            parts += piece
            remaining -= separator + piece.length
        }

        add(title, MAX_TITLE_CHARS)
        // 要約が長すぎて本文が全く入らなくならないよう、予算の半分までにする
        add(summary, budget / 2)
        add(content, profile.maxContentChars)
        return parts.joinToString(SEPARATOR)
    }

    /** 先頭から [n] 文字まで。サロゲートペアの途中で切らない。 */
    private fun String.takeSafely(n: Int): String {
        if (length <= n) return this
        val end = if (n > 0 && this[n - 1].isHighSurrogate()) n - 1 else n
        return substring(0, end)
    }
}
