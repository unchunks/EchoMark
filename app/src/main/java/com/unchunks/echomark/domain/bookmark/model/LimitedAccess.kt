package com.unchunks.echomark.domain.bookmark.model

/**
 * ページが全文を見せてくれなかった理由(#23)。取得した本文を、記事の全文として要約しないために使う。
 */
enum class PageAccessLimit {
    /** 有料・購読者限定(続きは購読が要る)。公開されている冒頭だけ取れる */
    PAYWALL,

    /** ログイン・会員登録が要る(無料の会員登録を含む)。公開されている冒頭だけ取れる */
    LOGIN_REQUIRED,

    /** ボット対策・アクセス拒否のページ(「Just a moment...」など)。ページの本文は取れていない */
    BLOCKED
}

/**
 * 一部しか取得できなかったページ(有料・会員限定)の扱い。
 * 保存する本文の先頭に [MARKER] の行を入れ、AI には取得できた範囲だけを要約させ(AiPrompts)、
 * 要約の末尾に [SUMMARY_SUFFIX] を付けて、全文の要約ではないことが分かるようにする。
 * 本文に印を残す形にしているのは、保存の形(DB のスキーマ)を変えずに、再処理しても判断が消えないようにするため。
 */
object LimitedAccess {
    /** 本文の先頭の行の見出し。AI への指示(AiPrompts)と判定が同じ文字列を使う */
    const val MARKER = "【一部のみ取得】"

    /** 要約の末尾に付ける注記 */
    const val SUMMARY_SUFFIX = "(一部のみ)"

    /** 本文の先頭に入れる行。 */
    fun note(limit: PageAccessLimit): String = when (limit) {
        PageAccessLimit.PAYWALL -> "$MARKER 有料または購読者限定のページのため、公開されている冒頭の一部だけを取得しました。"
        PageAccessLimit.LOGIN_REQUIRED -> "$MARKER ログインや会員登録が必要なページのため、公開されている冒頭の一部だけを取得しました。"
        // ページの本文を取れていないときは、印も本文に入れない(取得できなかったものとして扱う)
        PageAccessLimit.BLOCKED -> ""
    }

    /** 本文が、一部だけを取得したものか。 */
    fun isLimited(text: String): Boolean = text.contains(MARKER)

    /** 一部だけを取得した本文からの要約なら、末尾に [SUMMARY_SUFFIX] を付ける(付いていれば付けない)。 */
    fun annotateSummary(summary: String, sourceText: String): String =
        if (isLimited(sourceText) && summary.isNotBlank() && !summary.trimEnd().endsWith(SUMMARY_SUFFIX)) {
            summary.trimEnd() + SUMMARY_SUFFIX
        } else {
            summary
        }
}
