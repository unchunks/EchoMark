package com.unchunks.echomark.data.ai

/**
 * AI が書いた要約から、要約の本文ではない前置き(「この動画では、」・タイトル・チャンネル名など)を取り除く(純粋関数)。
 * 指示でも書かないよう伝えているが、端末内の小さいモデルは入力にあるタイトルやチャンネル名を要約の書き出しに写しやすいため、
 * 保存する前にここでも除く。取り除くのは書き出しの決まった形だけで、文の途中は変えない。
 */
object SummaryCleaner {

    /**
     * @param title ブックマークのタイトル(要約の書き出しに写されたら除く)
     * @param channel 動画の投稿者(チャンネル名)。分からなければ null
     */
    fun clean(summary: String, title: String, channel: String?): String {
        val original = summary.trim()
        var text = original
        // 「この動画では、「タイトル」は…」のように重なることがあるため、変わらなくなるまで数回繰り返す
        repeat(MAX_PASSES) {
            val next = stripOnce(text, title, channel)
            if (next == text) return@repeat
            text = next
        }
        // 取り除いた結果ほとんど残らないなら、書き出しも含めて内容だったとみなして元のままにする
        return if (text.length >= MIN_REMAINING_CHARS) text else original
    }

    /**
     * 本文の「チャンネル: 名前」の行(YouTube の本文の組み立て、HtmlContentExtractor.youtubeText)から投稿者を取り出す。
     * 無ければ null。
     */
    fun channelOf(text: String): String? =
        CHANNEL_LINE.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun stripOnce(text: String, title: String, channel: String?): String {
        var result = text
        result = META_PREFACE.replace(result, "")
        titleVariants(title).forEach { variant ->
            result = removeLeading(result, variant, TITLE_SUFFIX)
        }
        if (!channel.isNullOrBlank() && channel.trim().length >= MIN_NAME_CHARS) {
            result = removeLeading(result, channel.trim(), CHANNEL_SUFFIX)
            // 「(〇〇チャンネル)」のように括弧で添えたものは、書き出しでなくても除く
            result = result.replace(Regex("[(（]" + Regex.escape(channel.trim()) + "[)）]"), "")
        }
        return result.trimStart(*LEADING_JUNK)
    }

    /** [prefix](「」『』で囲んだものも含む)で始まっていれば、続く助詞などの [suffix] ごと除く。 */
    private fun removeLeading(text: String, prefix: String, suffix: String): String {
        val quoted = Regex.escape(prefix)
        val pattern = Regex("^(?:[「『\"]$quoted[」』\"]|$quoted)$suffix")
        return pattern.replace(text, "")
    }

    /** タイトルと、末尾のサイト名(" - YouTube" など)を除いたもの。 */
    private fun titleVariants(title: String): List<String> {
        val trimmed = title.trim().replace(WHITESPACE, " ")
        val withoutSite = SITE_SUFFIX.replace(trimmed, "").trim()
        return listOf(trimmed, withoutSite).filter { it.length >= MIN_TITLE_CHARS }.distinct()
    }

    private const val MAX_PASSES = 3

    /** 前置きを除いた後に、これより短くなったら除かない */
    private const val MIN_REMAINING_CHARS = 10

    /**
     * これより短いタイトルは除かない。「Kotlin」のような短いタイトルは、要約の主語として書き出しに来るのが自然なため
     * (「Kotlin は…」の主語を消してしまう)
     */
    private const val MIN_TITLE_CHARS = 8

    /** これより短いチャンネル名は、偶然の一致を避けるため除かない */
    private const val MIN_NAME_CHARS = 2

    private val WHITESPACE = Regex("""\s+""")

    /**
     * 書き出しの「この動画では、」「本記事は」「今回の動画で、」など。助詞まで続くときだけ除く
     * (「この記事の要点は」の「この記事」だけを消して「の要点は」を残さない)
     */
    private val META_PREFACE = Regex(
        "^(?:この|本|当|今回の)" +
            "(?:動画|記事|ページ|ブログ|エントリー?|投稿|資料|文書|論文|音声|配信|ポッドキャスト|メモ|画像|スライド|コンテンツ)" +
            "(?:の内容は|によると|において|では|は|で(?=[、，,]))[、，,:：]?\\s*"
    )

    /**
     * タイトルの後に続く「は、」「では、」「という動画で、」、または句読点。どれも続かないとき(「〜の内容を」のように
     * タイトルが文の一部になっているとき)は除かない
     */
    private const val TITLE_SUFFIX =
        "(?:(?:という(?:動画|記事|タイトルの(?:動画|記事))(?:で|では|は)?|では|は|の内容は|で(?=[、，,]))[、，,:：。]?|[、，,:：。])\\s*"

    /**
     * チャンネル名の後に続く「さんの動画で、」「による解説では」「さんが」など。
     * 「Google が発表した」のような、投稿者が話の主語になっている書き出しは残す(「の動画」「さん」などが続くときだけ除く)
     */
    private const val CHANNEL_SUFFIX =
        "(?:(?:さん|氏|チャンネル)?(?:の|による)(?:動画|配信|解説|チャンネル)(?:で|では|は)?|(?:さん|氏)(?:が|は)|チャンネル(?:で|では|は))" +
            "[、，,:：]?\\s*"

    /** タイトルの末尾のサイト名(「 - YouTube」「 | Zenn」など) */
    private val SITE_SUFFIX = Regex("""\s*[-|｜–—]\s*[^-|｜–—]{1,30}$""")

    private val CHANNEL_LINE = Regex("""(?m)^チャンネル: (.+)$""")

    private val LEADING_JUNK = charArrayOf(' ', '　', '、', '，', ',', '。', ':', '：')
}
