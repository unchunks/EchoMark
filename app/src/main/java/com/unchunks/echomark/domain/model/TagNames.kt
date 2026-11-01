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
     * 同じキーでも別のタグのことがあるので、タグの統合はユーザーに確認してから行う(自動ではしない)。
     * AI が出したタグを既存のタグにそろえる([resolve])ときは、これも使う。
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
     * AI が出したタグ名を、既存のタグ名に置き換える("android" → 既存の "Android"、"サーバ" → 既存の "サーバー")。
     * 次の順で探し、どれにも当たらないタグは [clean] した名前のまま新しいタグにする。空になったもの・重複は除く。
     * 1. [key] が同じ既存のタグ(全角/半角・大文字/小文字の違い)
     * 2. 別名([aliases]。統合・名前変更した元の名前 → 今のタグ名。キーは [key])
     * 3. [variantKey] が同じ既存のタグ(区切り・かな・語尾の長音の違い)
     * 4. [variantKey] が同じ別名
     * @param existing 既存のタグ名(優先する順。同じキーのタグが複数あれば先にあるものを使う)
     */
    fun resolve(candidates: List<String>, existing: List<String>, aliases: Map<String, String> = emptyMap()): List<String> {
        val existingByKey = HashMap<String, String>()
        val existingByVariantKey = HashMap<String, String>()
        existing.forEach { name ->
            existingByKey.putIfAbsent(key(name), name)
            variantKey(name).takeIf { it.isNotEmpty() }?.let { existingByVariantKey.putIfAbsent(it, name) }
        }
        val aliasesByVariantKey = HashMap<String, String>()
        aliases.forEach { (aliasKey, target) ->
            variantKey(aliasKey).takeIf { it.isNotEmpty() }?.let { aliasesByVariantKey.putIfAbsent(it, target) }
        }
        return candidates
            .map { clean(it) }
            .filter { it.isNotEmpty() }
            .map { name ->
                val variant = variantKey(name)
                existingByKey[key(name)] ?: aliases[key(name)] ?: existingByVariantKey[variant] ?: aliasesByVariantKey[variant] ?: name
            }
            .distinctBy { key(it) }
    }

    private const val VARIANT_SEPARATORS = "-_・.·/'‐–—"
    private const val PROLONGED_SOUND = 'ー'
    private val KATAKANA_RANGE = 'ァ'..'ヶ'
    private const val KATAKANA_TO_HIRAGANA = 0x60
}
