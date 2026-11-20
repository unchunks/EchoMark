package com.unchunks.echomark.domain.podcast

import java.text.Normalizer

/**
 * 読みの辞書の1件(表記 → 読み)。読み上げの直前に、表記を読みに置き換える。
 * 英数字だけの表記(`GitHub` など)は大文字・小文字を区別せず、前後が英数字でないところだけで置き換える
 * (`AI` が `MAIL` の中で当たらないように)。それ以外の表記は、文の中のどこでも置き換える。
 */
data class ReadingEntry(val surface: String, val reading: String) {

    /** 比べるための表記(全角・半角をそろえる。英数字だけなら小文字にする) */
    val matchKey: String get() = keyOf(surface)

    /** 英数字(と記号)だけの表記か。単語の境目でだけ置き換える */
    val isLatin: Boolean get() = surface.isNotEmpty() && surface.all { it.code < 0x80 }

    companion object {
        /** 表記の最大の長さ */
        const val MAX_SURFACE_CHARS = 40

        /** 読みの最大の長さ */
        const val MAX_READING_CHARS = 80

        /** 辞書に入れられる数の上限 */
        const val MAX_ENTRIES = 500

        /** 全角・半角をそろえ、前後の空白を除く */
        fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).trim()

        fun keyOf(surface: String): String = normalize(surface).let { s -> if (s.all { it.code < 0x80 }) s.lowercase() else s }

        /**
         * 利用者の入れた1件の誤り(画面に出す日本語)。正しければ null。
         * 読みは利用者が決めるものなので、かなに限らない(`ジーピーティー4` のように数字を混ぜてもよい)。
         */
        fun problemOf(surface: String, reading: String): String? {
            val s = normalize(surface)
            val r = normalize(reading)
            return when {
                s.isEmpty() -> "表記を入れてください"
                r.isEmpty() -> "読みを入れてください"
                s.length > MAX_SURFACE_CHARS -> "表記は${MAX_SURFACE_CHARS}文字までです"
                r.length > MAX_READING_CHARS -> "読みは${MAX_READING_CHARS}文字までです"
                keyOf(s) == keyOf(r) -> "読みが表記と同じです"
                else -> null
            }
        }

        /** かな(ひらがな・カタカナ・長音・中黒・空白)だけか。AI の出した読みは、これを満たすものだけ使う */
        fun isKana(text: String): Boolean =
            text.isNotBlank() && text.all { c ->
                c in 'ぁ'..'ゖ' || c in 'ァ'..'ヺ' || c == 'ー' || c == '・' || c == '゛' || c == '゜' || c == ' ' || c == '　'
            }
    }
}

/**
 * 読みの辞書。表記の重なり(全角・半角・大文字・小文字の違いを含む)は後から入れたものを残す。
 */
object ReadingDictionary {

    /**
     * [current] に [entry] を入れた辞書。同じ表記があれば置き換える(並びは新しいものを先にする)。
     * 表記・読みは全角・半角をそろえて入れる。
     */
    fun upsert(current: List<ReadingEntry>, entry: ReadingEntry): List<ReadingEntry> {
        val normalized = ReadingEntry(ReadingEntry.normalize(entry.surface), ReadingEntry.normalize(entry.reading))
        return (listOf(normalized) + current.filter { it.matchKey != normalized.matchKey }).take(ReadingEntry.MAX_ENTRIES)
    }

    fun remove(current: List<ReadingEntry>, surface: String): List<ReadingEntry> {
        val key = ReadingEntry.keyOf(surface)
        return current.filter { it.matchKey != key }
    }

    /**
     * バックアップから読み込んだもの([incoming])を今の辞書([current])に足す。同じ表記は今のものを残す
     * (この端末で直した読みを、古いファイルで戻さない)。誤りのあるものは足さない。
     */
    fun merge(current: List<ReadingEntry>, incoming: List<ReadingEntry>): List<ReadingEntry> {
        val keys = current.map { it.matchKey }.toMutableSet()
        val added = incoming.filter { ReadingEntry.problemOf(it.surface, it.reading) == null }
            .map { ReadingEntry(ReadingEntry.normalize(it.surface), ReadingEntry.normalize(it.reading)) }
            .filter { keys.add(it.matchKey) }
        return (current + added).take(ReadingEntry.MAX_ENTRIES)
    }
}
