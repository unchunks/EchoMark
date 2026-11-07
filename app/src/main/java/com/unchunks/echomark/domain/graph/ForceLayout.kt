package com.unchunks.echomark.domain.graph

import java.util.Random
import kotlin.math.max
import kotlin.math.sqrt

/** 点の位置。[x]・[y] は 0〜1 に収めた値で、[KnowledgeGraph.nodes] と同じ並び。 */
class GraphLayout(val x: FloatArray, val y: FloatArray) {
    val size: Int get() = x.size

    companion object {
        val EMPTY = GraphLayout(FloatArray(0), FloatArray(0))
    }
}

/**
 * 力指向の配置(Fruchterman-Reingold)。つながった点は引き合い、すべての点は反発し合う。
 * 画面の外へ出ないよう中心へのゆるい引力を足し、つながっていないまとまりも離れすぎない。
 *
 * 点が n 個なら 1 回に n² の計算で、[GraphOptions.maxNodes] の上限(300)なら 200 回で 1,800 万回ほど。
 * 重いので、呼ぶ側はバックグラウンドの Dispatcher で動かし、[isCancelled] で途中でやめられるようにする。
 * 乱数は固定の種から作るので、同じグラフは同じ配置になる(開くたびに形が変わらない)。
 */
object ForceLayout {
    const val DEFAULT_ITERATIONS = 200
    private const val SEED = 20241106L

    /** 画面の端に点が張り付かないよう、0〜1 の外側に空ける余白 */
    private const val MARGIN = 0.06f

    /** 中心への引力の強さ。弱いと、つながっていないまとまりが遠く離れ、まとまりの中の点が詰まって見える */
    private const val GRAVITY = 1.5f

    fun layout(
        graph: KnowledgeGraph,
        iterations: Int = DEFAULT_ITERATIONS,
        isCancelled: () -> Boolean = { false }
    ): GraphLayout {
        val n = graph.nodes.size
        if (n == 0) return GraphLayout.EMPTY
        if (n == 1) return GraphLayout(floatArrayOf(0.5f), floatArrayOf(0.5f))

        val random = Random(SEED)
        val px = FloatArray(n)
        val py = FloatArray(n)
        // まとまりごとに円周上の別の場所から始めると、初めから離れた位置になり、絡まりにくい
        val clusterCount = max(graph.clusters.size, 1)
        for (i in 0 until n) {
            val angle = 2.0 * Math.PI * graph.nodes[i].cluster / clusterCount
            px[i] = (0.5 + 0.3 * Math.cos(angle) + (random.nextDouble() - 0.5) * 0.2).toFloat()
            py[i] = (0.5 + 0.3 * Math.sin(angle) + (random.nextDouble() - 0.5) * 0.2).toFloat()
        }

        val k = sqrt(1.0 / n).toFloat()
        // 線の多い点(密なまとまりの中の点)に引力が集まりすぎて重ならないよう、引力は両端の点のつながりの数で割る
        val degree = IntArray(n)
        for (edge in graph.edges) {
            degree[edge.a]++
            degree[edge.b]++
        }
        val dx = FloatArray(n)
        val dy = FloatArray(n)
        for (iteration in 0 until iterations) {
            if (isCancelled()) break
            dx.fill(0f)
            dy.fill(0f)
            // 反発
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    var vx = px[i] - px[j]
                    var vy = py[i] - py[j]
                    var dist2 = vx * vx + vy * vy
                    if (dist2 < 1e-8f) {
                        // 重なっている点は、決まった向きにずらす
                        vx = (random.nextFloat() - 0.5f) * 1e-3f
                        vy = (random.nextFloat() - 0.5f) * 1e-3f
                        dist2 = vx * vx + vy * vy + 1e-8f
                    }
                    val force = k * k / dist2
                    dx[i] += vx * force
                    dy[i] += vy * force
                    dx[j] -= vx * force
                    dy[j] -= vy * force
                }
            }
            // 引力(線でつながった点どうし)
            for (edge in graph.edges) {
                val vx = px[edge.a] - px[edge.b]
                val vy = py[edge.a] - py[edge.b]
                val dist = sqrt(vx * vx + vy * vy) + 1e-6f
                // 引力の大きさは dist² / k(重い線ほど強く)。向きを含めた変位は v に dist / k を掛けたもの
                val share = sqrt(max(degree[edge.a], 1).toFloat() * max(degree[edge.b], 1).toFloat())
                val pull = dist / k * (0.5f + 0.5f * edge.weight.coerceAtMost(4f)) / share
                dx[edge.a] -= vx * pull
                dy[edge.a] -= vy * pull
                dx[edge.b] += vx * pull
                dy[edge.b] += vy * pull
            }
            // 中心へのゆるい引力
            for (i in 0 until n) {
                dx[i] += (0.5f - px[i]) * GRAVITY * k
                dy[i] += (0.5f - py[i]) * GRAVITY * k
            }
            // 動かせる距離を、だんだん小さくして落ち着かせる
            val temperature = 0.1f * (1f - iteration.toFloat() / iterations).coerceAtLeast(0.02f)
            for (i in 0 until n) {
                val length = sqrt(dx[i] * dx[i] + dy[i] * dy[i])
                if (length > 1e-9f) {
                    val step = minOf(length, temperature)
                    px[i] += dx[i] / length * step
                    py[i] += dy[i] / length * step
                }
            }
        }
        return normalize(px, py)
    }

    /** 全部の点が [MARGIN] 〜 1 - [MARGIN] に収まるよう、広がりをそろえる(縦横の比は保つ)。 */
    internal fun normalize(px: FloatArray, py: FloatArray): GraphLayout {
        val minX = px.min()
        val maxX = px.max()
        val minY = py.min()
        val maxY = py.max()
        val span = max(max(maxX - minX, maxY - minY), 1e-6f)
        val scale = (1f - 2 * MARGIN) / span
        val offsetX = (1f - (maxX - minX) * scale) / 2f
        val offsetY = (1f - (maxY - minY) * scale) / 2f
        return GraphLayout(
            FloatArray(px.size) { (px[it] - minX) * scale + offsetX },
            FloatArray(py.size) { (py[it] - minY) * scale + offsetY }
        )
    }
}
