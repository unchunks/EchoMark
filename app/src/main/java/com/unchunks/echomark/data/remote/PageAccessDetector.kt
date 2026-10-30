package com.unchunks.echomark.data.remote

import com.unchunks.echomark.domain.bookmark.model.PageAccessLimit
import org.jsoup.nodes.Document

/**
 * 取得したページが、全文を見せてくれていないか(有料・会員限定・ログインが要る・ボット対策のページ)を見分ける(#23)。
 * 純粋関数(JVM 単体テスト可能)。誤って「一部のみ」と判断すると要約に注記が付くため、
 * 文言だけでは決めず、取り出せた本文の短さと組み合わせる(全文のある記事に「会員限定」の見出しがあるだけなら、限定とは見なさない)。
 */
object PageAccessDetector {

    /**
     * ページ内の目印。[Document] から [markupOf] で作る。
     * @property structuredPaywall 構造化データ(JSON-LD)に `isAccessibleForFree: false` がある
     * @property paywallElement ペイウォールを表すクラス名・id の要素がある
     */
    data class Markup(val structuredPaywall: Boolean = false, val paywallElement: Boolean = false)

    /** ノイズを除く前の [document] から、ページ内の目印を拾う。 */
    fun markupOf(document: Document): Markup {
        val structured = document.select("script[type=application/ld+json]").any { STRUCTURED_PAYWALL.containsMatchIn(it.data()) }
        val element = document.select("[class], [id]").any { el ->
            val names = (el.className() + " " + el.id()).lowercase()
            PAYWALL_NAMES.any { names.contains(it) }
        }
        return Markup(structuredPaywall = structured, paywallElement = element)
    }

    /**
     * @param title ページのタイトル
     * @param pageText ページ全体の文字(ノイズを除く前)。文言を探す
     * @param extractedChars 本文として取り出せた文字数
     * @return 全文を見せていないページならその理由。通常のページなら null
     */
    fun detect(title: String?, pageText: String, extractedChars: Int, markup: Markup = Markup()): PageAccessLimit? {
        val normalizedPage = normalize(pageText)
        val head = normalize(title.orEmpty()) + normalizedPage.take(BLOCK_PROBE_CHARS)
        if (extractedChars < MAX_BLOCKED_BODY_CHARS && BLOCKED_PHRASES.any { head.contains(it) }) return PageAccessLimit.BLOCKED
        if (extractedChars < MAX_PREVIEW_CHARS && LOGIN_PHRASES.any { normalizedPage.contains(it) }) return PageAccessLimit.LOGIN_REQUIRED
        val paywallPhrase = PAYWALL_PHRASES.any { normalizedPage.contains(it) }
        return when {
            paywallPhrase && extractedChars < MAX_PREVIEW_CHARS -> PageAccessLimit.PAYWALL
            markup.structuredPaywall && extractedChars < MAX_PREVIEW_CHARS -> PageAccessLimit.PAYWALL
            markup.paywallElement && extractedChars < MAX_SHORT_PREVIEW_CHARS -> PageAccessLimit.PAYWALL
            else -> null
        }
    }

    /** 空白を除いて小文字にする(「Just a moment」や「続きを 読む」のような空白の入り方の違いを吸収する)。 */
    private fun normalize(text: String): String = text.lowercase().replace(WHITESPACE, "")

    /** 続きを見せるための冒頭の長さの上限。これより長く取れているときは、全文があるとみなす */
    private const val MAX_PREVIEW_CHARS = 2_500
    private const val MAX_SHORT_PREVIEW_CHARS = 1_500

    /** ボット対策のページの本文はごく短い */
    private const val MAX_BLOCKED_BODY_CHARS = 800
    private const val BLOCK_PROBE_CHARS = 1_000

    private val WHITESPACE = Regex("""\s+""")
    private val STRUCTURED_PAYWALL = Regex(""""isAccessibleForFree"\s*:\s*"?false"?""", RegexOption.IGNORE_CASE)

    private val PAYWALL_NAMES = listOf("paywall", "member-only", "members-only", "subscriber-only", "subscribers-only")

    private val BLOCKED_PHRASES = listOf(
        "justamoment", "checkingyourbrowser", "verifyyouarehuman", "enablejavascriptandcookiestocontinue", "accessdenied",
        "attentionrequired", "アクセスが拒否", "アクセスを制限", "ボットではないことを確認", "403forbidden", "areyouarobot"
    )

    private val LOGIN_PHRASES = listOf(
        "ログインして続きを", "ログインすると続きを", "続きを読むにはログイン", "会員登録(無料)で続き", "会員登録（無料）で続き", "会員登録して続きを", "無料会員登録で続き",
        "signintocontinuereading", "logintocontinuereading", "signintoreadthefull", "logintoreadthefull", "createafreeaccounttocontinue",
        "registertocontinuereading"
    )

    private val PAYWALL_PHRASES = listOf(
        "有料会員限定", "有料会員になると", "有料プラン限定", "購読者限定", "この記事は会員限定", "会員限定記事", "続きを読むには",
        "続きは有料", "この先は有料", "続きは購読", "プレミアム会員限定", "購読すると続き",
        "subscribetocontinuereading", "subscribetoread", "subscribersonly", "thisarticleisforsubscribers", "paidsubscribersonly",
        "unlockthisarticle", "becomeamembertocontinue", "continuereadingwithasubscription"
    )
}
