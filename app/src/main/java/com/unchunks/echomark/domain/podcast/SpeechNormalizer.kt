package com.unchunks.echomark.domain.podcast

import java.text.Normalizer

/**
 * 台本の文を、読み上げで読み間違えにくい形に整える(純粋関数。JVM 単体テスト可能)。
 *
 * 次の順に整える。先に決めた読みは、後の整えで触らない([SpeechPiece.Reading])。
 * 1. 読めないものを除く: コード・URL・メールアドレス・HTML のタグ・Markdown の記号・引用番号・絵文字。全角・半角をそろえる
 * 2. 文の中の振り仮名(`東京(とうきょう)`)を読みにする
 * 3. 利用者の読みの辞書(いちばん先に効く)
 * 4. AI が台本から見つけた読み
 * 5. 電話番号・番号は1けたずつ読ませる([SpeechPiece.Telephone]・[SpeechPiece.Digits])
 * 6. 数の書き方: 日付(`2026/11/20` → 2026年11月20日)・曜日・時刻(`10:30` → 10時30分)・範囲(`10〜20` → 10から20)・
 *    通貨(`$100` → 100ドル)・掛け算・版(`v2.1` → バージョン2.1)
 * 7. 数の後ろの単位(`5km` → 5キロメートル)
 * 8. ドメイン名の点(`example.com` → example ドット com)
 * 9. アプリに入れてある読み(よく出る略語・製品名)
 * 10. 残った大文字の略語は1文字ずつ読む(`NHK` → エヌエイチケー)。5文字以上で母音のあるものは単語として読ませる
 * 11. 残った記号を、読む言葉(`&` → アンド)か区切り(`|` → 、)にするか、除く
 */
object SpeechNormalizer {

    /**
     * @param dictionary 利用者の読みの辞書
     * @param aiReadings AI が台本から見つけた読み(かなだけのもの)
     */
    fun normalize(
        text: String,
        dictionary: List<ReadingEntry> = emptyList(),
        aiReadings: List<ReadingEntry> = emptyList()
    ): SpeechText {
        var pieces: List<SpeechPiece> = listOf(SpeechPiece.Plain(clean(text)))
        pieces = pieces.splitPlain(ANNOTATION) { match ->
            listOf(SpeechPiece.Reading(match.value, match.groupValues[2], ReadingOrigin.ANNOTATION))
        }
        pieces = pieces.applyEntries(dictionary, ReadingOrigin.USER)
        pieces = pieces.applyEntries(aiReadings.filter { ReadingEntry.isKana(it.reading) }, ReadingOrigin.AI)
        pieces = pieces.splitPlain(TELEPHONE) { listOf(SpeechPiece.Telephone(it.value)) }
        pieces = pieces.splitPlain(DIGIT_CODE) { listOf(SpeechPiece.Digits(it.value)) }
        pieces = pieces.mapPlain(::rewriteNumbers)
        pieces = pieces.splitPlain(UNIT) { match ->
            val unit = match.groupValues[1]
            listOf(SpeechPiece.Reading(unit, UNITS.getValue(unit), ReadingOrigin.BUILT_IN))
        }
        pieces = pieces.mapPlain { plain -> DOMAIN.replace(plain) { it.value.replace(".", " ドット ") } }
        pieces = pieces.applyEntries(BuiltInReadings.entries, ReadingOrigin.BUILT_IN)
        pieces = pieces.splitPlain(ACRONYM) { match ->
            val letters = match.groupValues[1]
            if (letters.length >= MIN_WORD_LIKE_LETTERS && letters.any { it in VOWELS }) null
            else listOf(SpeechPiece.Reading(match.value, letters.map { LETTERS.getValue(it) }.joinToString(""), ReadingOrigin.SPELLED))
        }
        pieces = pieces.mapPlain(::rewriteSymbols)
        return SpeechText(tidy(SpeechText.merged(pieces)))
    }

    /** 読み上げても分からないもの(コード・URL・記号の飾り・絵文字)を除き、全角・半角をそろえる */
    fun clean(text: String): String {
        var s = text.replace(CODE_BLOCK, "\n")
        s = s.replace(MARKDOWN_LINK, "$1")
        s = s.replace(HTML_TAG, "")
        s = Normalizer.normalize(s, Normalizer.Form.NFKC)
        s = s.replace(URL, "").replace(EMAIL, "")
        // 短いインラインのコードは名前(ファイル名・関数名)のことが多いので、記号を外して読む。長いものは読まない
        s = s.replace(INLINE_CODE) { m -> m.groupValues[1].takeIf { it.length <= MAX_INLINE_CODE_CHARS }.orEmpty() }
        s = s.replace(CITATION, "")
        s = s.replace(LINE_MARKER, "")
        s = s.replace(EMPHASIS, "")
        s = removeEmoji(s)
        return s
    }

    private fun removeEmoji(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val drop = when {
                cp == '°'.code -> false // 単位で読む
                cp == 0xFE0F || cp == 0xFE0E || cp == 0x200D || cp == 0x20E3 -> true
                cp in 0x1F3FB..0x1F3FF -> true
                else -> Character.getType(cp) == Character.OTHER_SYMBOL.toInt()
            }
            if (!drop) appendCodePoint(cp)
            i += Character.charCount(cp)
        }
    }

    /** 数の書き方を、読み上げで迷わない形にする */
    internal fun rewriteNumbers(text: String): String {
        var s = text
        s = FULL_DATE.replace(s) { m ->
            val (y, mo, d) = m.destructured
            if (isMonthDay(mo, d)) "${y}年${mo.toInt()}月${d.toInt()}日" else m.value
        }
        s = YEAR_MONTH.replace(s) { m ->
            val (y, mo) = m.destructured
            if (mo.toInt() in 1..12) "${y}年${mo.toInt()}月" else m.value
        }
        s = MONTH_DAY.replace(s) { m ->
            val (mo, d) = m.destructured
            if (isMonthDay(mo, d)) "${mo.toInt()}月${d.toInt()}日" else m.value
        }
        s = WEEKDAY.replace(s) { m -> if (m.groupValues[1] == "祝") "、祝日" else "、${m.groupValues[1]}曜日" }
        s = TIME.replace(s) { m ->
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toInt()
            val second = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
            if (hour > MAX_HOUR || minute > 59 || (second != null && second > 59)) m.value
            else buildString {
                append("${hour}時")
                if (minute > 0 || second != null) append("${minute}分")
                if (second != null) append("${second}秒")
            }
        }
        s = YEAR_RANGE.replace(s, "$1から$2")
        s = RANGE.replace(s, "から")
        s = CURRENCY_BEFORE.replace(s) { m -> m.groupValues[2] + CURRENCIES.getValue(m.groupValues[1]) }
        s = MULTIPLY.replace(s, "$1かける$2")
        s = TIMES.replace(s, "$1倍")
        s = VERSION.replace(s, "バージョン$1")
        s = HYPHEN_IN_NAME.replace(s, " ")
        s = MINUS.replace(s, "マイナス")
        s = NUMBER_MARK.replace(s, "ナンバー$1")
        return s
    }

    private fun isMonthDay(month: String, day: String): Boolean = month.toInt() in 1..12 && day.toInt() in 1..31

    /** 残った記号を、読む言葉か区切りにするか、除く */
    internal fun rewriteSymbols(text: String): String {
        var s = text
        s = s.replace(LAUGH, "")
        s = s.replace(ARROW, "、")
        s = SYMBOL_WORDS.entries.fold(s) { acc, (symbol, word) -> acc.replace(symbol, word) }
        s = s.replace(PAUSE_SYMBOLS, "、")
        s = s.replace(DROP_SYMBOLS, "")
        s = s.replace(LONE_HYPHEN, "、")
        return s
    }

    /** 区切りの重なり・空白を整える */
    private fun tidy(pieces: List<SpeechPiece>): List<SpeechPiece> {
        val tidied = pieces.mapIndexed { index, piece ->
            if (piece !is SpeechPiece.Plain) return@mapIndexed piece
            var s = piece.spoken
            s = s.replace(Regex("""[ \t　]+"""), " ")
            s = s.replace(Regex(""" *\n[ \n]*"""), "\n")
            s = s.replace(Regex("""、[ 、]*(?=[。!?\n])"""), "")
            s = s.replace(Regex("""、[ 、]*"""), "、")
            s = s.replace(Regex("""(?<=[　-鿿＀-￯]) +| +(?=[　-鿿＀-￯])"""), "")
            if (index == 0) s = s.trimStart(' ', '、', '\n')
            if (index == pieces.lastIndex) s = s.trimEnd(' ', '、', '\n')
            SpeechPiece.Plain(s)
        }
        return SpeechText.merged(tidied)
    }

    // --- 部分ごとの置き換え ---

    private inline fun List<SpeechPiece>.mapPlain(transform: (String) -> String): List<SpeechPiece> =
        map { if (it is SpeechPiece.Plain) SpeechPiece.Plain(transform(it.spoken)) else it }

    /** ふつうの文の中の [regex] に当たるところを、[make] の部分にする。[make] が null なら、そのまま残す */
    private fun List<SpeechPiece>.splitPlain(regex: Regex, make: (MatchResult) -> List<SpeechPiece>?): List<SpeechPiece> =
        flatMap { piece ->
            if (piece !is SpeechPiece.Plain) return@flatMap listOf(piece)
            val out = mutableListOf<SpeechPiece>()
            var last = 0
            for (match in regex.findAll(piece.spoken)) {
                val replacement = make(match) ?: continue
                if (match.range.first > last) out += SpeechPiece.Plain(piece.spoken.substring(last, match.range.first))
                out += replacement
                last = match.range.last + 1
            }
            if (last < piece.spoken.length) out += SpeechPiece.Plain(piece.spoken.substring(last))
            out
        }

    /** 辞書の表記を読みにする。長い表記を先に当てる */
    private fun List<SpeechPiece>.applyEntries(entries: List<ReadingEntry>, origin: ReadingOrigin): List<SpeechPiece> {
        val usable = entries.filter { ReadingEntry.normalize(it.surface).isNotEmpty() && ReadingEntry.normalize(it.reading).isNotEmpty() }
        if (usable.isEmpty()) return this
        val readings = usable.reversed().associate { it.matchKey to ReadingEntry.normalize(it.reading) } // 重なれば前のものを残す
        val regex = entriesRegex(usable)
        return splitPlain(regex) { match ->
            val reading = readings[ReadingEntry.keyOf(match.value)] ?: return@splitPlain null
            listOf(SpeechPiece.Reading(match.value, reading, origin))
        }
    }

    private fun entriesRegex(entries: List<ReadingEntry>): Regex {
        val alternatives = entries.map { ReadingEntry.normalize(it.surface) }.distinct().sortedByDescending { it.length }.map { surface ->
            if (surface.all { it.code < 0x80 }) {
                // 英数字の表記は、大文字・小文字を区別せず、前後が英数字でないところだけ
                val start = if (surface.first().isLetterOrDigit()) "(?<![A-Za-z0-9])" else ""
                val end = if (surface.last().isLetterOrDigit()) "(?![A-Za-z0-9])" else ""
                "$start(?i:${Regex.escape(surface)})$end"
            } else {
                Regex.escape(surface)
            }
        }
        return Regex(alternatives.joinToString("|"))
    }

    // --- 決まり ---

    private const val MAX_HOUR = 30 // 深夜の放送の 25:00 なども時刻として読む
    private const val MIN_WORD_LIKE_LETTERS = 5
    private val VOWELS = setOf('A', 'E', 'I', 'O', 'U')

    private val CODE_BLOCK = Regex("""```[\s\S]*?(```|$)""")
    private val INLINE_CODE = Regex("""`([^`\n]*)`""")
    private const val MAX_INLINE_CODE_CHARS = 30
    private val MARKDOWN_LINK = Regex("""\[([^\]\n]+)]\((?:[^)\s]+)\)""")
    private val HTML_TAG = Regex("""<[A-Za-z/!][^<>\n]{0,200}>""")
    private val URL = Regex("""(?i)\b(?:https?://|www\.)[^\s、。」』)\]]+""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val CITATION = Regex("""\[\d{1,3}(?:\s*[,、]\s*\d{1,3})*]""")
    private val LINE_MARKER = Regex("""(?m)^[ \t]*(?:#{1,6}[ \t]+|>[ \t]?|[-*+][ \t]+|\d{1,2}[.)][ \t]+)""")
    private val EMPHASIS = Regex("""\*\*|__|~~""")

    /** 漢字の後ろの振り仮名。`東京(とうきょう)`・`日本橋(ニホンバシ)` */
    private val ANNOTATION = Regex("""([\p{IsHan}々〆ヶ]+)\(([ぁ-ゖァ-ヺー]+)\)""")

    /** 電話番号(0 で始まり、ハイフンで3つに分かれるもの) */
    private val TELEPHONE = Regex("""(?<![\d-])0\d{1,4}-\d{1,4}-\d{3,4}(?![\d-])""")

    /** 0 で始まる4けた以上の番号・8けた以上の区切りの無い数(数の大きさとしては読まない) */
    private val DIGIT_CODE = Regex("""(?<![\d.,])(?:0\d{3,}|\d{8,})(?![\d.,])""")

    private val FULL_DATE = Regex("""(?<![\d/.-])((?:19|20)\d{2})[/.-](\d{1,2})[/.-](\d{1,2})(?![\d/.-])""")
    private val YEAR_MONTH = Regex("""(?<![\d/.-])((?:19|20)\d{2})[/.](\d{1,2})(?![\d/.])""")
    private val MONTH_DAY = Regex("""(?<![\d/.])(\d{1,2})/(\d{1,2})(?![\d/.%])""")
    private val WEEKDAY = Regex("""(?<=[\d日])\s?\(([月火水木金土日祝])(?:曜日?)?\)""")
    private val TIME = Regex("""(?<![\d:])(\d{1,2}):(\d{2})(?::(\d{2}))?(?![\d:])""")
    private val YEAR_RANGE = Regex("""(?<!\d)((?:19|20)\d{2})\s?[-–—]\s?((?:19|20)\d{2})(?!\d)""")

    /** 数・時刻・日付の範囲の波線(`10〜20`・`3日〜5日`・`1,000円~`) */
    private val RANGE = Regex("""(?<=[\d年月日時分秒円人件個%])\s?[〜~～]\s?|\s?[〜~～]\s?(?=\d)""")

    private val CURRENCIES = mapOf("$" to "ドル", "¥" to "円", "€" to "ユーロ", "£" to "ポンド")
    private val CURRENCY_BEFORE = Regex("""([$¥€£])\s?(\d[\d,]*(?:\.\d+)?(?:万|億|兆)?)""")
    private val MULTIPLY = Regex("""(\d)\s?[x×]\s?(\d)""")
    private val TIMES = Regex("""(\d)[x×](?![A-Za-z\d])""")
    private val VERSION = Regex("""(?<![A-Za-z])(?:[vV]|[Vv]er\.?\s?|[Vv]ersion\s)(\d+(?:\.\d+)*)""")
    private val NUMBER_MARK = Regex("""(?<![A-Za-z])No\.\s?(\d)""")

    /** 名前の中の英字と数字の間のハイフン(`GPT-4`・`4-door`)。区切りの空白にする */
    private val HYPHEN_IN_NAME = Regex("""(?<=[A-Za-z])-(?=\d)|(?<=\d)-(?=[A-Za-z])""")

    /** 数の前のマイナス(`-5度`)。前が英数字でないときだけ */
    private val MINUS = Regex("""(?<![A-Za-z0-9.)])-(?=\d)""")

    /** 数の後ろの単位。長いものから当てる */
    private val UNITS: Map<String, String> = linkedMapOf(
        "km/h" to "キロメートル毎時", "kWh" to "キロワットアワー", "mAh" to "ミリアンペアアワー",
        "Mbps" to "メガビーピーエス", "Gbps" to "ギガビーピーエス", "GHz" to "ギガヘルツ", "MHz" to "メガヘルツ", "kHz" to "キロヘルツ",
        "TB" to "テラバイト", "GB" to "ギガバイト", "MB" to "メガバイト", "KB" to "キロバイト", "kB" to "キロバイト",
        "km" to "キロメートル", "cm" to "センチメートル", "mm" to "ミリメートル", "kg" to "キログラム", "mg" to "ミリグラム",
        "mL" to "ミリリットル", "ml" to "ミリリットル", "kW" to "キロワット", "fps" to "エフピーエス", "dpi" to "ディーピーアイ",
        "ppm" to "ピーピーエム", "min" to "分", "sec" to "秒", "ms" to "ミリ秒", "px" to "ピクセル", "pt" to "ポイント",
        "Hz" to "ヘルツ", "°C" to "度", "°" to "度", "m" to "メートル", "g" to "グラム", "L" to "リットル", "W" to "ワット", "V" to "ボルト"
    )
    private val UNIT = Regex("""(?<=\d)\s?(${UNITS.keys.joinToString("|") { Regex.escape(it) }})(?![A-Za-z])""")

    /** よく使うトップレベルドメインで終わるドメイン名 */
    private val DOMAIN = Regex("""(?<![A-Za-z0-9@.])[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.(?:com|net|org|jp|io|dev|co|app|ai|me|info|tv)(?![A-Za-z0-9])""")

    /** 大文字の略語(後ろの複数形の s も含める)。`APIs`・`NHK`・`A社` */
    private val ACRONYM = Regex("""(?<![A-Za-z])([A-Z]{1,6})s?(?![A-Za-z])""")

    private val LETTERS: Map<Char, String> = mapOf(
        'A' to "エー", 'B' to "ビー", 'C' to "シー", 'D' to "ディー", 'E' to "イー", 'F' to "エフ", 'G' to "ジー", 'H' to "エイチ",
        'I' to "アイ", 'J' to "ジェー", 'K' to "ケー", 'L' to "エル", 'M' to "エム", 'N' to "エヌ", 'O' to "オー", 'P' to "ピー",
        'Q' to "キュー", 'R' to "アール", 'S' to "エス", 'T' to "ティー", 'U' to "ユー", 'V' to "ブイ", 'W' to "ダブリュー",
        'X' to "エックス", 'Y' to "ワイ", 'Z' to "ゼット"
    )

    private val LAUGH = Regex("""(?<![A-Za-z])w{2,}(?![A-Za-z])""")
    private val ARROW = Regex("""\s?(?:->|=>|[→⇒⇨➡←⇐↑↓])\s?""")
    private val SYMBOL_WORDS = linkedMapOf(" & " to "アンド", "&" to "アンド", "@" to "アット", "+" to "プラス", "=" to "イコール")
    private val PAUSE_SYMBOLS = Regex("""\s?[|/【】\[\]〔〕《》<>]\s?""")
    private val DROP_SYMBOLS = Regex("""[〜~#*_^\\{}※†‡§¶•■□◆◇▲△▼▽●○◎★☆♪♫♡♥]""")
    private val LONE_HYPHEN = Regex("""\s[-–—]+\s""")
}
