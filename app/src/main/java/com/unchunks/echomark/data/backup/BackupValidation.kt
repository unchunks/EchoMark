package com.unchunks.echomark.data.backup

/**
 * 読み込む前に、バックアップの中身がそろっているかを確かめる(書き込む前に全体を確かめ、途中まで読み込むことをしない)。
 *
 * 書き出しは DB の外部キーを守った1回のトランザクションの内容なので、正しいファイルなら次の問題は起きない。
 * 起きていれば、ファイルが壊れている・書き換えられている。そのときは何も書き込まずに断る
 * (読める分だけ取り込むと、ノートや復習のカードが外れるなど、利用者の気づかないところでデータが欠ける)。
 * - 読み取れない要素がある([DecodedBackup.invalidRecords])
 * - 同じ表の中で ID が重なっている(ブックマーク・タグ・会話・メッセージ)
 * - DB で外部キーのある参照(紐付け・メッセージ・ノート・外したタグ・復習のカード・要約の履歴・コレクション・タグの別名・
 *   リンク切れの確認)が、ファイルの中に無いものを指している
 *
 * 外部キーの無い参照(会話の質問の対象・メッセージが引用したブックマーク・タグの親)は、もとから消えたものを指すことがあるので
 * 問題にしない(読み込みで外す)。
 */
object BackupValidation {

    /** 問題の一覧(画面に出せる短い文言)。空なら読み込んでよい */
    fun problemsOf(decoded: DecodedBackup): List<String> {
        val data = decoded.data
        val problems = mutableListOf<String>()
        if (decoded.invalidRecords > 0) problems += "読み取れない要素が ${decoded.invalidRecords} 件あります"

        fun <T> duplicates(what: String, items: List<T>, id: (T) -> Long) {
            val count = items.size - items.map(id).toSet().size
            if (count > 0) problems += "$what の ID が $count 件重なっています"
        }
        duplicates("ブックマーク", data.bookmarks) { it.id }
        duplicates("タグ", data.tags) { it.id }
        duplicates("会話", data.conversations) { it.id }
        duplicates("メッセージ", data.messages) { it.id }

        val bookmarkIds = data.bookmarks.mapTo(HashSet()) { it.id }
        val tagIds = data.tags.mapTo(HashSet()) { it.id }
        val conversationIds = data.conversations.mapTo(HashSet()) { it.id }
        fun dangling(what: String, refs: List<Long>, existing: Set<Long>) {
            val count = refs.count { it !in existing }
            if (count > 0) problems += "$what のうち $count 件が、ファイルに無いものを指しています"
        }
        dangling("タグの紐付け(ブックマーク)", data.bookmarkTags.map { it.bookmarkId }, bookmarkIds)
        dangling("タグの紐付け(タグ)", data.bookmarkTags.map { it.tagId }, tagIds)
        dangling("メッセージ", data.messages.map { it.conversationId }, conversationIds)
        dangling("ノート", data.notes.map { it.bookmarkId }, bookmarkIds)
        dangling("外したタグの記録", data.removedTags.map { it.bookmarkId }, bookmarkIds)
        dangling("復習のカード", data.reviewCards.map { it.bookmarkId }, bookmarkIds)
        dangling("要約の履歴", data.summaryHistory.map { it.bookmarkId }, bookmarkIds)
        dangling("コレクションのブックマーク", data.collections.flatMap { c -> c.items.map { it.bookmarkId } }, bookmarkIds)
        dangling("タグの別名", data.tagAliases.map { it.tagId }, tagIds)
        dangling("リンク切れの確認をしないブックマーク", data.linkCheckMutedBookmarkIds, bookmarkIds)
        return problems
    }

    /** [problems] を、読み込めなかった理由として画面に出す文言にする */
    fun message(problems: List<String>): String =
        "バックアップが壊れているか、書き換えられているため、何も読み込みませんでした(今のデータはそのままです)。\n" +
            problems.joinToString("\n") { "・$it" }
}
