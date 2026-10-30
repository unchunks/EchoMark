package com.unchunks.echomark.domain.model

import java.text.Normalizer
import java.util.Locale

/** タグ名の表記ゆれの吸収(AI が付けるタグを既存のタグにそろえる)。 */
object TagNames {

    /** 表示・保存用に整える: 全角英数などを NFKC で正規化し、前後の空白と先頭の「#」を除く。 */
    fun clean(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFKC).trim().trimStart('#').trim()

    /** 同じタグとみなすためのキー(全角/半角・大文字/小文字を区別しない)。 */
    fun key(name: String): String = clean(name).lowercase(Locale.ROOT)

    /**
     * 表記ゆれの候補を見つけるための、[key] よりゆるいキー。
     * 空白・「-」「_」「・」「.」などの区切りを無視し、ひらがな/カタカナを区別せず、語尾の長音(コンピューター/コンピュータ)も無視する。
     * 同じキーでも別のタグのことがあるので、タグの統合はユーザーに確認してから行う(自動では使わない)。
     */
    fun variantKey(name: String): String {
        val folded = key(name)
            .filterNot { it.isWhitespace() || it in VARIANT_SEPARATORS }
            .map { if (it in KATAKANA_RANGE && it != PROLONGED_SOUND) (it.code - KATAKANA_TO_HIRAGANA).toChar() else it }
            .joinToString("")
        return folded.trimEnd(PROLONGED_SOUND).ifEmpty { folded }
    }

    /**
     * [name] と同じタグを [existing] から探す。名前が完全に一致するものを優先し、無ければ [key] が一致するもの
     * ("android" → 既存の "Android")。名前の変更・統合で、統合先になるタグを決めるのに使う。
     */
    fun findSame(name: String, existing: List<String>): String? =
        existing.firstOrNull { it == name } ?: key(name).let { key -> existing.firstOrNull { key(it) == key } }

    /**
     * AI が出したタグ名を、表記ゆれの範囲で一致する既存のタグ名に置き換える("android" → 既存の "Android")。
     * 一致するものが無いタグは [clean] した名前のまま新しいタグにする。空になったもの・重複は除く。
     * @param existing 既存のタグ名(優先する順。同じキーのタグが複数あれば先にあるものを使う)
     */
    fun resolve(candidates: List<String>, existing: List<String>): List<String> {
        val existingByKey = HashMap<String, String>()
        existing.forEach { name -> existingByKey.putIfAbsent(key(name), name) }
        return candidates
            .map { clean(it) }
            .filter { it.isNotEmpty() }
            .distinctBy { key(it) }
            .map { existingByKey[key(it)] ?: it }
    }

    private const val VARIANT_SEPARATORS = "-_・.·/'‐–—"
    private const val PROLONGED_SOUND = 'ー'
    private val KATAKANA_RANGE = 'ァ'..'ヶ'
    private const val KATAKANA_TO_HIRAGANA = 0x60
}
