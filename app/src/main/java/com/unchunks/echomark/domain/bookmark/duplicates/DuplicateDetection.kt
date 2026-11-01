package com.unchunks.echomark.domain.bookmark.duplicates

import com.unchunks.components.websearch.ResourceSearcher
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark

/**
 * 重複・関連の見つけ方のしきい値(ObjectBox COSINE の距離 = 1 - コサイン類似度。小さいほど近い)。
 *
 * **この値は実機で評価していない。** `docs/embedding-evaluation.md` の評価データで「関連あり」の距離を測ってはいるが、
 * 「同じ内容」と「同じ話題」の境目を決めるデータはまだ無い。検索の採用の上限(`EmbeddingModelProfile.maxSearchDistance` = 0.4)
 * より十分に小さい値から始め、利用者が [DuplicateSensitivity] で変えられるようにしてある。
 * 値を直すときはこのファイルだけを変える(画面・保存側にしきい値は書かない)。
 */
enum class DuplicateSensitivity(
    /** この距離以下を「同じ内容かもしれない」候補にする */
    val nearDuplicateDistance: Double,
    /** この距離以下をつなげて「関連するまとまり」にする。[nearDuplicateDistance] より大きい */
    val relatedDistance: Double
) {
    STRICT(nearDuplicateDistance = 0.06, relatedDistance = 0.20),
    STANDARD(nearDuplicateDistance = 0.10, relatedDistance = 0.26),
    LOOSE(nearDuplicateDistance = 0.16, relatedDistance = 0.32);

    companion object {
        val DEFAULT = STANDARD
    }
}

/** 2件のブックマークの組。[firstId] < [secondId] にそろえるので、順番が逆でも同じ組になる。 */
data class DuplicatePair(val firstId: Long, val secondId: Long) {
    operator fun contains(id: Long): Boolean = id == firstId || id == secondId

    /** 設定に保存するときの文字列 */
    val key: String get() = "$firstId:$secondId"

    companion object {
        fun of(a: Long, b: Long): DuplicatePair = if (a <= b) DuplicatePair(a, b) else DuplicatePair(b, a)

        /** [key] で保存した文字列を戻す。壊れていれば null */
        fun parse(key: String): DuplicatePair? {
            val parts = key.split(':')
            if (parts.size != 2) return null
            val a = parts[0].toLongOrNull() ?: return null
            val b = parts[1].toLongOrNull() ?: return null
            return if (a == b) null else of(a, b)
        }
    }
}

enum class DuplicateReason {
    /** 同じ URL(末尾の「/」・`www.`・追跡用のクエリなどの違いは同じとみなす) */
    SAME_URL,

    /** 中身が近い(URL は違う) */
    SIMILAR_CONTENT
}

/** 重複の候補1組。[distance] は [DuplicateReason.SIMILAR_CONTENT] のときの距離(URL の一致では null)。 */
data class DuplicateCandidate(val pair: DuplicatePair, val reason: DuplicateReason, val distance: Double?)

/** ベクトルの近傍検索の結果1件分: [bookmarkId] から見て [otherId] が [distance] の距離にある。 */
data class VectorNeighbor(val bookmarkId: Long, val otherId: Long, val distance: Double)

/** ブックマークの ID と URL(URL の一致を調べる入力)。 */
data class UrlEntry(val id: Long, val url: String)

/** 関連するまとまり([ids] は古い順)。[closest] はまとまりの中で一番近い組の距離。 */
data class RelatedCluster(val ids: List<Long>, val closest: Double)

/** 重複・関連を見つける純粋なロジック(ベクトルの検索そのものは data 層でする)。 */
object DuplicateDetector {

    /** 同じ URL かを見るときに無視するクエリ(`utm_*` は [ResourceSearcher.normalizeUrl] が除く) */
    private val TRACKING_PARAMS = setOf(
        "fbclid", "gclid", "gbraid", "wbraid", "msclkid", "yclid", "igshid", "twclid", "mc_cid", "mc_eid",
        "ref_src", "ref_url", "_ga"
    )

    /** 1つのまとまりの人数の上限。つなげすぎて「全部が関連」にならないようにする */
    const val MAX_GROUP_SIZE = 12

    /** これ未満の人数はまとまりとして出さない(2件の近さは、詳細画面の「関連」で見られる) */
    const val MIN_GROUP_SIZE = 3

    /**
     * 同じ URL かを見るためのキー。http(s) 以外・空は null。
     * [ResourceSearcher.normalizeUrl] に加えて、フラグメントと追跡用のクエリ([TRACKING_PARAMS])を除く。
     */
    fun urlKey(url: String?): String? {
        val trimmed = url?.trim().orEmpty()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) return null
        val withoutFragment = trimmed.substringBefore('#')
        val base = withoutFragment.substringBefore('?')
        val kept = withoutFragment.substringAfter('?', "").split('&')
            .filter { it.isNotEmpty() && it.substringBefore('=').lowercase() !in TRACKING_PARAMS }
        val rebuilt = if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
        return ResourceSearcher.normalizeUrl(rebuilt)
    }

    /** URL が同じ(キーが同じ)ものの組。3件以上が同じなら、一番古い(ID の小さい)ものとほかのそれぞれの組にする */
    fun sameUrlCandidates(urls: List<UrlEntry>): List<DuplicateCandidate> =
        urls.mapNotNull { entry -> urlKey(entry.url)?.let { it to entry.id } }
            .groupBy({ it.first }, { it.second })
            .values
            .filter { it.size >= 2 }
            .flatMap { ids ->
                val sorted = ids.sorted()
                sorted.drop(1).map { DuplicateCandidate(DuplicatePair.of(sorted.first(), it), DuplicateReason.SAME_URL, null) }
            }

    /**
     * 重複の候補。URL が同じ組を先に、続けて中身が近い順に並べる。
     * 同じ組が両方で見つかったら URL の一致を採る。[dismissed](「両方残す」と答えた組)は除く。
     */
    fun candidates(
        urls: List<UrlEntry>,
        neighbors: List<VectorNeighbor>,
        sensitivity: DuplicateSensitivity,
        dismissed: Set<DuplicatePair> = emptySet()
    ): List<DuplicateCandidate> {
        val byPair = LinkedHashMap<DuplicatePair, DuplicateCandidate>()
        sameUrlCandidates(urls).forEach { byPair[it.pair] = it }
        neighbors.asSequence()
            .filter { it.bookmarkId != it.otherId && it.distance <= sensitivity.nearDuplicateDistance }
            .forEach { n ->
                val pair = DuplicatePair.of(n.bookmarkId, n.otherId)
                val existing = byPair[pair]
                if (existing == null) {
                    byPair[pair] = DuplicateCandidate(pair, DuplicateReason.SIMILAR_CONTENT, n.distance)
                } else if (existing.reason == DuplicateReason.SIMILAR_CONTENT && n.distance < (existing.distance ?: Double.MAX_VALUE)) {
                    byPair[pair] = existing.copy(distance = n.distance)
                }
            }
        return byPair.values
            .filter { it.pair !in dismissed }
            .sortedWith(
                compareBy<DuplicateCandidate> { it.reason != DuplicateReason.SAME_URL }
                    .thenBy { it.distance ?: 0.0 }
                    .thenBy { it.pair.firstId }
                    .thenBy { it.pair.secondId }
            )
    }

    /**
     * 関連するまとまり。[sensitivity] の [DuplicateSensitivity.relatedDistance] 以下の組を、近いものから順につないでいく
     * (単連結)。つなぐと [MAX_GROUP_SIZE] を超えるときはつながない(連鎖で大きな塊になるのを防ぐ)。
     * [MIN_GROUP_SIZE] 未満のものは出さない。人数の多い順、同じなら近い順。
     */
    fun relatedClusters(neighbors: List<VectorNeighbor>, sensitivity: DuplicateSensitivity): List<RelatedCluster> {
        val edges = neighbors
            .filter { it.bookmarkId != it.otherId && it.distance <= sensitivity.relatedDistance }
            .groupBy { DuplicatePair.of(it.bookmarkId, it.otherId) }
            .map { (pair, list) -> pair to list.minOf { it.distance } }
            .sortedWith(compareBy({ it.second }, { it.first.firstId }, { it.first.secondId }))

        val parent = HashMap<Long, Long>()
        val size = HashMap<Long, Int>()
        val closest = HashMap<Long, Double>()
        fun find(x: Long): Long {
            var root = x
            while ((parent[root] ?: root) != root) root = parent.getValue(root)
            var node = x
            while (node != root) {
                val next = parent.getValue(node)
                parent[node] = root
                node = next
            }
            return root
        }
        for ((pair, distance) in edges) {
            listOf(pair.firstId, pair.secondId).forEach {
                parent.putIfAbsent(it, it)
                size.putIfAbsent(it, 1)
            }
            val a = find(pair.firstId)
            val b = find(pair.secondId)
            if (a == b) continue
            val merged = size.getValue(a) + size.getValue(b)
            if (merged > MAX_GROUP_SIZE) continue
            parent[b] = a
            size[a] = merged
            // つなぐ順が近い順なので、まとまりで最初につないだ距離が一番近い
            closest[a] = minOf(closest[a] ?: distance, closest[b] ?: distance, distance)
        }
        return parent.keys.groupBy { find(it) }
            .filter { it.value.size >= MIN_GROUP_SIZE }
            .map { (root, ids) -> RelatedCluster(ids.sorted(), closest[root] ?: 0.0) }
            .sortedWith(compareByDescending<RelatedCluster> { it.ids.size }.thenBy { it.closest }.thenBy { it.ids.first() })
    }

    /**
     * まとまりの名前。2件以上に付いているタグのうち一番多いもの(同数なら名前順)、無ければ一番多いカテゴリ、
     * それも無ければ先頭のブックマークのタイトル。
     */
    fun groupLabel(members: List<Bookmark>): String {
        members.flatMap { it.tags.distinct() }.groupingBy { it }.eachCount()
            .filter { it.value >= 2 }
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.key })
            .firstOrNull()?.let { return it.key }
        members.mapNotNull { it.category?.takeIf(String::isNotBlank) }.groupingBy { it }.eachCount()
            .filter { it.value >= 2 }
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.key })
            .firstOrNull()?.let { return it.key }
        return members.firstOrNull()?.title.orEmpty()
    }

    /**
     * 2件のうち、残すのにふさわしい方(おすすめの表示用。決めるのは利用者)。
     * AI の処理が終わっている・要約がある・オフラインコピーがある・タグが多い・お気に入り・読んだ、が多い方。
     * 同点なら先に保存した方(ID の小さい方)。
     */
    fun suggestKeeper(a: Bookmark, b: Bookmark): Bookmark {
        val scoreA = keepScore(a)
        val scoreB = keepScore(b)
        return when {
            scoreA != scoreB -> if (scoreA > scoreB) a else b
            a.createdAt != b.createdAt -> if (a.createdAt < b.createdAt) a else b
            else -> if (a.id < b.id) a else b
        }
    }

    private fun keepScore(bookmark: Bookmark): Int =
        (if (bookmark.aiStatus == AiStatus.DONE) 2 else 0) +
            (if (!bookmark.summary.isNullOrBlank()) 2 else 0) +
            (if (bookmark.hasSnapshot) 1 else 0) +
            bookmark.tags.size.coerceAtMost(5) +
            (if (bookmark.isFavorite) 2 else 0) +
            (if (bookmark.isRead) 1 else 0)
}

/**
 * 重複を1件にまとめるときの、残すブックマークへの引き継ぎ。
 * 残す方([keep])を主とし、消す方([remove])にしか無い情報だけを足す(上書きしない)。
 * 要約・本文・カテゴリ・タグ以外の AI の結果は、残す方のものをそのまま使う(タグは [tagsToAdd] で足す)。
 */
object BookmarkMerge {

    /** 引き継いだあとの値(DB に書く列だけ。それ以外は [keep] のまま) */
    data class Fields(
        val createdAt: Long,
        val lastAccessedAt: Long,
        val isFavorite: Boolean,
        val isArchived: Boolean,
        val readAt: Long?,
        val content: String?,
        val snapshotPath: String?,
        val snapshotPdfPath: String?,
        val snapshotAt: Long?
    ) {
        companion object {
            fun of(bookmark: Bookmark) = Fields(
                createdAt = bookmark.createdAt,
                lastAccessedAt = bookmark.lastAccessedAt,
                isFavorite = bookmark.isFavorite,
                isArchived = bookmark.isArchived,
                readAt = bookmark.readAt,
                content = bookmark.content,
                snapshotPath = bookmark.snapshotPath,
                snapshotPdfPath = bookmark.snapshotPdfPath,
                snapshotAt = bookmark.snapshotAt
            )
        }
    }

    /**
     * - 保存日時は古い方、最後に開いた日時は新しい方
     * - お気に入りはどちらかがそうなら残す。アーカイブは両方がアーカイブのときだけ(片方が通常なら通常の一覧に出す)
     * - 読んだ日時はどちらかが読んでいれば古い方。本文が空なら [remove] の本文(メモ)を引き継ぐ
     * - オフラインコピーは、[keep] に無いときだけ [remove] のものを引き継ぐ
     */
    fun merge(keep: Bookmark, remove: Bookmark): Fields {
        val moveSnapshot = keep.snapshotPath == null && remove.snapshotPath != null
        return Fields(
            createdAt = minOf(keep.createdAt, remove.createdAt),
            lastAccessedAt = maxOf(keep.lastAccessedAt, remove.lastAccessedAt),
            isFavorite = keep.isFavorite || remove.isFavorite,
            isArchived = keep.isArchived && remove.isArchived,
            readAt = listOfNotNull(keep.readAt, remove.readAt).minOrNull(),
            content = keep.content?.takeIf { it.isNotBlank() } ?: remove.content?.takeIf { it.isNotBlank() } ?: keep.content,
            snapshotPath = if (moveSnapshot) remove.snapshotPath else keep.snapshotPath,
            snapshotPdfPath = if (moveSnapshot) remove.snapshotPdfPath else keep.snapshotPdfPath,
            snapshotAt = if (moveSnapshot) remove.snapshotAt else keep.snapshotAt
        )
    }

    /** [keep] に足すタグ。[remove] のタグのうち、[keep] に無いもの([keep] に AI のタグとしてあるものをユーザーのタグにする場合も含む) */
    fun tagsToAdd(keep: Bookmark, remove: Bookmark): List<String> =
        remove.tags.filter { tag ->
            tag !in keep.tags || (tag !in remove.aiTags && tag in keep.aiTags)
        }
}
