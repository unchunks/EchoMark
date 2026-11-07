package com.unchunks.echomark.data.backup

import com.unchunks.echomark.domain.repository.BackupSkipped

/** 読み込むのに使えるバックアップと、読み飛ばしたもの */
class SanitizedBackup(val data: BackupData, val skipped: List<BackupSkipped>)

/**
 * 読み込む前に、バックアップの中身を確かめ、壊れた箇所だけを外す(全体を確かめてから書き込む。書き込みは1つのトランザクション)。
 *
 * 書き出しは DB の外部キーを守った1回のトランザクションの内容なので、正しいファイルなら外すものは無い。
 * 外したものは、種類ごとに数と理由を画面に出す(気づかないままデータが欠けないように)。
 * - 読み取れない要素([DecodedBackup.invalidByArray])
 * - 同じ表の中で ID が重なっている要素(ブックマーク・タグ・会話・メッセージ。最初のものを残す)
 * - DB で外部キーのある参照(紐付け・メッセージ・ノート・外したタグ・復習のカード・要約の履歴・コレクション・タグの別名・
 *   リンク切れの確認)が、ファイルに無い(または上で外した)ものを指している要素
 *
 * 外部キーの無い参照(会話の質問の対象・メッセージが引用したブックマーク・タグの親・分けた元の会話)は、もとから消えたものを
 * 指すことがあるので外さない(読み込みで外す)。zip の中身が決まった形でないとき([BackupArchive])は、ここに来る前に全体を断る。
 */
object BackupValidation {

    /** JSON の配列の名前 → 画面に出す種類の名前 */
    private val LABELS = mapOf(
        "bookmarks" to "ブックマーク",
        "tags" to "タグ",
        "bookmarkTags" to "タグの紐付け",
        "conversations" to "会話",
        "messages" to "メッセージ",
        "tagAliases" to "タグの別名",
        "removedTags" to "外したタグの記録",
        "categories" to "カテゴリ",
        "smartFolders" to "スマートフォルダ",
        "subscriptions" to "購読",
        "notes" to "ノート",
        "collections" to "コレクション",
        "reviewCards" to "復習のカード",
        "summaryHistory" to "要約の履歴",
        "chatTemplates" to "チャットのテンプレート",
        "settings" to "設定"
    )

    const val REASON_UNREADABLE = "読み取れない形でした"
    const val REASON_DUPLICATE = "ID がほかと重なっていました"
    const val REASON_MISSING_PARENT = "もとになるもの(ブックマーク・タグ・会話)がファイルに無いか、読み飛ばしました"

    fun sanitize(decoded: DecodedBackup): SanitizedBackup {
        val skipped = mutableListOf<BackupSkipped>()
        fun skip(label: String, count: Int, reason: String) {
            if (count > 0) skipped += BackupSkipped(label, count, reason)
        }
        decoded.invalidByArray.forEach { (array, count) -> skip(LABELS[array] ?: array, count, REASON_UNREADABLE) }

        var data = decoded.data
        fun <T> firstById(label: String, items: List<T>, id: (T) -> Long): List<T> {
            val seen = HashSet<Long>()
            val kept = items.filter { seen.add(id(it)) }
            skip(label, items.size - kept.size, REASON_DUPLICATE)
            return kept
        }
        data = data.copy(
            bookmarks = firstById("ブックマーク", data.bookmarks) { it.id },
            tags = firstById("タグ", data.tags) { it.id },
            conversations = firstById("会話", data.conversations) { it.id },
            messages = firstById("メッセージ", data.messages) { it.id }
        )

        val bookmarkIds = data.bookmarks.mapTo(HashSet()) { it.id }
        val tagIds = data.tags.mapTo(HashSet()) { it.id }
        val conversationIds = data.conversations.mapTo(HashSet()) { it.id }
        fun <T> withParents(label: String, items: List<T>, hasParents: (T) -> Boolean): List<T> {
            val kept = items.filter(hasParents)
            skip(label, items.size - kept.size, REASON_MISSING_PARENT)
            return kept
        }
        val collectionItems = data.collections.sumOf { it.items.size }
        val collections = data.collections.map { collection -> collection.copy(items = collection.items.filter { it.bookmarkId in bookmarkIds }) }
        skip("コレクションのブックマーク", collectionItems - collections.sumOf { it.items.size }, REASON_MISSING_PARENT)
        val muted = data.linkCheckMutedBookmarkIds.filter { it in bookmarkIds }
        skip("リンク切れの確認をしない印", data.linkCheckMutedBookmarkIds.size - muted.size, REASON_MISSING_PARENT)

        data = data.copy(
            bookmarkTags = withParents("タグの紐付け", data.bookmarkTags) { it.bookmarkId in bookmarkIds && it.tagId in tagIds },
            messages = withParents("メッセージ", data.messages) { it.conversationId in conversationIds },
            notes = withParents("ノート", data.notes) { it.bookmarkId in bookmarkIds },
            removedTags = withParents("外したタグの記録", data.removedTags) { it.bookmarkId in bookmarkIds },
            reviewCards = withParents("復習のカード", data.reviewCards) { it.bookmarkId in bookmarkIds },
            summaryHistory = withParents("要約の履歴", data.summaryHistory) { it.bookmarkId in bookmarkIds },
            tagAliases = withParents("タグの別名", data.tagAliases) { it.tagId in tagIds },
            collections = collections,
            linkCheckMutedBookmarkIds = muted
        )
        // 同じ種類・同じ理由はまとめる(読み取れない要素と、重なった ID の両方があるときなど)
        val merged = skipped.groupBy { it.label to it.reason }.map { (key, items) -> BackupSkipped(key.first, items.sumOf { it.count }, key.second) }
        return SanitizedBackup(data, merged)
    }
}
