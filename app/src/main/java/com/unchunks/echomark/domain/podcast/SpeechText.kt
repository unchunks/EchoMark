package com.unchunks.echomark.domain.podcast

/** 読みをどこから決めたか(テストと、画面で「辞書に入れる」候補を出すのに使う)。 */
enum class ReadingOrigin {
    /** 文の中の振り仮名(`漢字(かんじ)`) */
    ANNOTATION,

    /** 利用者の読みの辞書 */
    USER,

    /** AI が台本から見つけた読み */
    AI,

    /** アプリに入れてある読み(よく出る略語・製品名・単位) */
    BUILT_IN,

    /** 大文字の略語を1文字ずつ読む */
    SPELLED
}

/**
 * 読み上げに渡す文の部分。[spoken] を順につなげたものを読み上げる。
 * Android の読み上げに渡すとき、[Telephone]・[Digits] には `TtsSpan` を付ける(付けても付けなくても、そのまま読める文にしてある)。
 */
sealed interface SpeechPiece {
    /** 読み上げに渡す文字 */
    val spoken: String

    /** ふつうの文。後の整え(数字・記号など)の対象 */
    data class Plain(override val spoken: String) : SpeechPiece

    /** 表記を読みに置き換えたもの。後の整えでは触らない */
    data class Reading(val surface: String, override val spoken: String, val origin: ReadingOrigin) : SpeechPiece

    /** 電話番号(1けたずつ区切って読ませる) */
    data class Telephone(override val spoken: String) : SpeechPiece

    /** 番号(会員番号・郵便番号など。数の大きさでなく、1けたずつ読ませる) */
    data class Digits(override val spoken: String) : SpeechPiece
}

/** 読み上げ用に整えた文。 */
data class SpeechText(val pieces: List<SpeechPiece>) {
    /** 読み上げに渡す文(部分をつなげたもの) */
    val plain: String get() = pieces.joinToString("") { it.spoken }

    /** 置き換えた読み(表記 → 読み)。同じ表記は1つにまとめる */
    val readings: List<SpeechPiece.Reading> get() = pieces.filterIsInstance<SpeechPiece.Reading>().distinctBy { it.surface }

    fun isBlank(): Boolean = plain.isBlank()

    /**
     * 読み上げに1回で渡す単位に分ける。文の終わり(。!?・改行)で区切り、[maxChars] を超えないように詰める
     * (1文が長すぎれば、読点・空白で、それも無ければ文字数で切る)。読みに置き換えた部分は途中で切らない。
     */
    fun chunks(maxChars: Int): List<SpeechText> {
        require(maxChars > 0)
        val sentences = mutableListOf<MutableList<SpeechPiece>>(mutableListOf())
        for (piece in pieces) {
            if (piece !is SpeechPiece.Plain) {
                sentences.last() += piece
                continue
            }
            var rest = piece.spoken
            while (rest.isNotEmpty()) {
                val end = rest.indexOfFirst { it in SENTENCE_ENDS }
                if (end < 0) {
                    sentences.last() += SpeechPiece.Plain(rest)
                    break
                }
                sentences.last() += SpeechPiece.Plain(rest.substring(0, end + 1))
                sentences += mutableListOf<SpeechPiece>()
                rest = rest.substring(end + 1)
            }
        }
        val result = mutableListOf<SpeechText>()
        var current = mutableListOf<SpeechPiece>()
        var currentLength = 0
        for (sentence in sentences.flatMap { splitLong(it, maxChars) }) {
            val length = sentence.sumOf { it.spoken.length }
            if (length == 0) continue
            if (currentLength > 0 && currentLength + length > maxChars) {
                result += SpeechText(current)
                current = mutableListOf()
                currentLength = 0
            }
            current += sentence
            currentLength += length
        }
        if (currentLength > 0) result += SpeechText(current)
        return result.map { SpeechText(merged(it.pieces)) }.filterNot { it.isBlank() }
    }

    private fun splitLong(sentence: List<SpeechPiece>, maxChars: Int): List<List<SpeechPiece>> {
        if (sentence.sumOf { it.spoken.length } <= maxChars) return listOf(sentence)
        val parts = mutableListOf<MutableList<SpeechPiece>>(mutableListOf())
        var length = 0
        fun add(piece: SpeechPiece) {
            if (length > 0 && length + piece.spoken.length > maxChars) {
                parts += mutableListOf<SpeechPiece>()
                length = 0
            }
            parts.last() += piece
            length += piece.spoken.length
        }
        for (piece in sentence) {
            if (piece !is SpeechPiece.Plain || piece.spoken.length <= maxChars) {
                // 読みの部分は切らない。ふつうの文は、読点・空白の後ろで区切って詰める
                if (piece is SpeechPiece.Plain) BREAK_AFTER.findAll(piece.spoken).map { it.value }.forEach { add(SpeechPiece.Plain(it)) }
                else add(piece)
                continue
            }
            BREAK_AFTER.findAll(piece.spoken).map { it.value }.flatMap { it.chunked(maxChars) }.forEach { add(SpeechPiece.Plain(it)) }
        }
        return parts
    }

    companion object {
        /** 文の終わり(整えた文は全角・半角をそろえてあるため、感嘆符・疑問符は半角) */
        private val SENTENCE_ENDS = setOf('。', '!', '?', '\n')

        /** 読点・空白までを1つにする(区切りの文字は前に付ける) */
        private val BREAK_AFTER = Regex("""[^、,\s]*[、,\s]+|[^、,\s]+""")

        /** 隣り合うふつうの文をつなげる */
        fun merged(pieces: List<SpeechPiece>): List<SpeechPiece> {
            val out = mutableListOf<SpeechPiece>()
            for (piece in pieces) {
                if (piece.spoken.isEmpty()) continue
                val last = out.lastOrNull()
                if (piece is SpeechPiece.Plain && last is SpeechPiece.Plain) out[out.lastIndex] = SpeechPiece.Plain(last.spoken + piece.spoken)
                else out += piece
            }
            return out
        }
    }
}
