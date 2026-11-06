package com.unchunks.echomark.domain.graph

import com.unchunks.echomark.domain.bookmark.duplicates.DuplicateDetector
import com.unchunks.echomark.domain.bookmark.model.Bookmark

/** つながりの理由。 */
enum class EdgeKind {
    /** 中身が近い(関連のまとまりに入っている。#81) */
    RELATED,

    /** 同じタグが付いている */
    TAG,

    /** 両方 */
    BOTH
}

/** グラフの点(ブックマーク1件)。[cluster] はつながりのまとまりの番号(大きいまとまりほど小さい番号)。 */
data class GraphNode(
    val bookmark: Bookmark,
    val cluster: Int,
    /** つながっている相手の数 */
    val degree: Int
) {
    val id: Long get() = bookmark.id
}

/**
 * グラフの線。[a]・[b] は [KnowledgeGraph.nodes] の位置(a < b)。[weight] は太さの目安(関連 = 2、共通のタグ 1 つにつき 1)。
 */
data class GraphEdge(
    val a: Int,
    val b: Int,
    val kind: EdgeKind,
    val weight: Float,
    /** 共通のタグ(関連だけでつながっているときは空) */
    val sharedTags: List<String>
)

/** つながりのまとまり1つ。[label] は名前(一番多い共通のタグなど)。 */
data class GraphCluster(val index: Int, val label: String, val size: Int)

/**
 * ナレッジグラフ。
 * @param candidateCount つながりを持つブックマークの数(点に出せた数より多いとき、つながりの多いものだけを出している)
 */
data class KnowledgeGraph(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val clusters: List<GraphCluster>,
    val candidateCount: Int
) {
    val isTruncated: Boolean get() = candidateCount > nodes.size

    /** 点 [index] につながる線 */
    fun edgesOf(index: Int): List<GraphEdge> = edges.filter { it.a == index || it.b == index }

    companion object {
        val EMPTY = KnowledgeGraph(emptyList(), emptyList(), emptyList(), 0)
    }
}

/** グラフを作る条件。 */
data class GraphOptions(
    /** 出す点の上限(数千件あっても、つながりの多いものだけを描く) */
    val maxNodes: Int = KnowledgeGraphBuilder.DEFAULT_MAX_NODES,
    /** このタグが付いたブックマークだけで作る(null なら全部)。絞り込みに使ったタグは、つながりの理由にしない */
    val tagFilter: String? = null
)

/**
 * ブックマークの「関連のまとまり」(#81)と、共通のタグから、ナレッジグラフ(点と線)を作る純粋な処理。
 *
 * 数千件でも使えるよう、次の順で絞る。
 * 1. 線の元になる組を数える: 関連のまとまりの中の全ての組と、付いている数が [MAX_TAG_FANOUT] 件以下のタグの中の全ての組
 *    (付いている数が多すぎるタグは、何とでもつながってしまい意味が薄いので使わない)
 * 2. つながりを持つブックマークを、つながりの強い順に [GraphOptions.maxNodes] 件までにする
 * 3. 残った点どうしの線だけを残す
 */
object KnowledgeGraphBuilder {
    const val DEFAULT_MAX_NODES = 120
    const val MAX_NODES = 300

    /** この件数を超えて付いているタグは、つながりに使わない */
    const val MAX_TAG_FANOUT = 12

    /** 関連のまとまりでつながっているときの太さ */
    const val RELATED_WEIGHT = 2f

    /** 名前を付けるまとまりの最小の大きさ(これ未満の小さなまとまりは、名前の無い「その他」にまとめる) */
    private const val MIN_CLUSTER_SIZE = 2

    /**
     * @param groups 関連のまとまり([com.unchunks.echomark.domain.repository.RelatedGroup] の中身のブックマークの ID)
     */
    fun build(bookmarks: List<Bookmark>, groups: List<List<Long>>, options: GraphOptions = GraphOptions()): KnowledgeGraph {
        val maxNodes = options.maxNodes.coerceIn(2, MAX_NODES)
        val pool = bookmarks.filter { options.tagFilter == null || options.tagFilter in it.tags }
        if (pool.size < 2) return KnowledgeGraph.EMPTY
        val byId = pool.associateBy { it.id }

        // 1. 組ごとの重みと、共通のタグ
        val pairs = HashMap<IdPair, PairInfo>()
        for (group in groups) {
            val members = group.filter { it in byId }.distinct().sorted()
            for (i in members.indices) for (j in i + 1 until members.size) {
                pairs.getOrPut(IdPair(members[i], members[j])) { PairInfo() }.related = true
            }
        }
        val byTag = HashMap<String, MutableList<Long>>()
        for (bookmark in pool) {
            for (tag in bookmark.tags.distinct()) {
                if (tag != options.tagFilter) byTag.getOrPut(tag) { ArrayList() } += bookmark.id
            }
        }
        for ((tag, ids) in byTag) {
            if (ids.size < 2 || ids.size > MAX_TAG_FANOUT) continue
            val sorted = ids.sorted()
            for (i in sorted.indices) for (j in i + 1 until sorted.size) {
                pairs.getOrPut(IdPair(sorted[i], sorted[j])) { PairInfo() }.tags += tag
            }
        }
        if (pairs.isEmpty()) return KnowledgeGraph.EMPTY

        // 2. つながりの強い順に点を選ぶ
        val strength = HashMap<Long, Float>()
        val neighbors = HashMap<Long, MutableSet<Long>>()
        for ((pair, info) in pairs) {
            val (a, b) = pair
            val w = info.weight()
            strength.merge(a, w, Float::plus)
            strength.merge(b, w, Float::plus)
            neighbors.getOrPut(a) { HashSet() } += b
            neighbors.getOrPut(b) { HashSet() } += a
        }
        val candidates = strength.keys
        val selected = candidates
            .sortedWith(compareByDescending<Long> { strength.getValue(it) }.thenByDescending { it })
            .take(maxNodes)
        val indexById = selected.withIndex().associate { (index, id) -> id to index }

        // 3. 選んだ点どうしの線
        val rawEdges = pairs.mapNotNull { (pair, info) ->
            val (x, y) = pair
            val ix = indexById[x] ?: return@mapNotNull null
            val iy = indexById[y] ?: return@mapNotNull null
            val a = minOf(ix, iy)
            val b = maxOf(ix, iy)
            GraphEdge(
                a = a, b = b,
                kind = when {
                    info.related && info.tags.isNotEmpty() -> EdgeKind.BOTH
                    info.related -> EdgeKind.RELATED
                    else -> EdgeKind.TAG
                },
                weight = info.weight(),
                sharedTags = info.tags.sorted()
            )
        }.sortedWith(compareBy({ it.a }, { it.b }))

        // つながりのまとまり(選んだ点の中で線でつながっているもの)。大きい順に番号を付ける
        val parent = IntArray(selected.size) { it }
        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var node = x
            while (parent[node] != root) {
                val next = parent[node]
                parent[node] = root
                node = next
            }
            return root
        }
        for (edge in rawEdges) {
            val ra = find(edge.a)
            val rb = find(edge.b)
            if (ra != rb) parent[rb] = ra
        }
        val members = selected.indices.groupBy { find(it) }.values
            .sortedWith(compareByDescending<List<Int>> { it.size }.thenBy { it.min() })
        val clusterOf = IntArray(selected.size)
        val clusters = members.mapIndexed { index, list ->
            list.forEach { clusterOf[it] = index }
            val label = if (list.size < MIN_CLUSTER_SIZE) "" else DuplicateDetector.groupLabel(list.map { byId.getValue(selected[it]) })
            GraphCluster(index, label, list.size)
        }
        val degree = IntArray(selected.size)
        rawEdges.forEach { degree[it.a]++; degree[it.b]++ }
        val nodes = selected.mapIndexed { index, id -> GraphNode(byId.getValue(id), clusterOf[index], degree[index]) }
        return KnowledgeGraph(nodes, rawEdges, clusters, candidateCount = candidates.size)
    }

    /** 組(a < b)。 */
    private data class IdPair(val a: Long, val b: Long)

    private class PairInfo {
        var related = false
        val tags = ArrayList<String>(2)

        fun weight(): Float = (if (related) RELATED_WEIGHT else 0f) + tags.size
    }
}
