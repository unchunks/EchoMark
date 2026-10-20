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
}
