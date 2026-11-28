package com.unchunks.echomark.domain.linkcheck

import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/** リンク1件の確認(1回分)の結果。 */
sealed interface LinkProbeResult {
    /**
     * 開けた。また、ボット除けなどで開かせてくれない(401・403・429 など)ときも、サイトは生きているのでこれにする。
     */
    data object Alive : LinkProbeResult

    /**
     * 開けなかった。[reason] は画面にそのまま出せる文言。
     * [networkLevel] は通信そのものの失敗(名前が引けない・接続できない・時間切れ)。端末がオフラインでも起きるので、
     * 端末が通信できないときは失敗に数えない(呼び出し側で確かめる)。
     */
    data class Failed(val reason: String, val networkLevel: Boolean) : LinkProbeResult

    /** 確認の対象にできない(ローカルネットワークのアドレス・URL の形式が不正など)。失敗にも成功にも数えない */
    data object NotCheckable : LinkProbeResult
}

/** ブックマークごとのリンク切れの確認の状態([com.unchunks.echomark.data.local.entity.LinkCheckEntity] と同じ意味)。 */
data class LinkCheckState(
    val checkedAt: Long = 0L,
    val failureCount: Int = 0,
    val firstFailedAt: Long? = null,
    val deadAt: Long? = null,
    val lastReason: String? = null,
    val muted: Boolean = false
) {
    /** リンク切れと判断している */
    val isDead: Boolean get() = deadAt != null
}

/** 確認する候補1件。[state] は確認前なら null。 */
data class LinkCandidate(val bookmarkId: Long, val url: String, val state: LinkCheckState? = null)

/**
 * リンク切れの確認(#94)の決まり。純粋な処理(JVM 単体テストできる)。
 *
 * **1回の失敗では切れたことにしない。** 一時的な障害・メンテナンス・端末の通信の不調を切れたと誤って知らせないよう、
 * 続けて [DEAD_AFTER_FAILURES] 回失敗し、かつ最初の失敗から [DEAD_AFTER_MILLIS] 以上たったときだけ切れたことにする。
 * 確認は夜に1回なので、ふつうは3晩続けて開けなかったときになる。1回でも開けたら記録は消える。
 * しきい値や間隔を変えるときは、このオブジェクトの定数だけを変える。
 */
object LinkCheckPolicy {
    /** 切れたことにする、続けて失敗した回数 */
    const val DEAD_AFTER_FAILURES = 3

    /** 切れたことにする、最初の失敗からの経過時間 */
    val DEAD_AFTER_MILLIS: Long = TimeUnit.DAYS.toMillis(2)

    /** 開けているリンクを確認し直す間隔(毎晩全部は確認しない) */
    val ALIVE_RECHECK_MILLIS: Long = TimeUnit.DAYS.toMillis(14)

    /** 失敗が続いているリンクを確認し直す間隔(次の夜に確認できるよう、1日より少し短くする) */
    val FAILING_RECHECK_MILLIS: Long = TimeUnit.HOURS.toMillis(20)

    /** 切れたリンクが戻っていないか確認し直す間隔 */
    val DEAD_RECHECK_MILLIS: Long = TimeUnit.DAYS.toMillis(7)

    /** 1晩に確認するリンクの数の上限 */
    const val MAX_PER_RUN = 100

    /** 1晩に同じサイト(ホスト)へ確認するリンクの数の上限。残りは次の夜に回す */
    const val MAX_PER_HOST_PER_RUN = 5

    /** 同じサイトへの確認の間隔の下限(ミリ秒) */
    const val HOST_INTERVAL_MILLIS = 2_000L

    /**
     * HTTP のステータスコード [code] から、確認の結果を決める。
     * 2xx・3xx は開けた。401・403・405・406・429・451・999 はサイトが応答しているので生きているとみなす(開かせないだけ)。
     * 404・410 はページが無い。5xx・408 はサイトの不具合。そのほかの 4xx も失敗。
     */
    fun classifyStatus(code: Int): LinkProbeResult = when (code) {
        in 200..399 -> LinkProbeResult.Alive
        401, 403, 405, 406, 429, 451, 999 -> LinkProbeResult.Alive
        404, 410 -> LinkProbeResult.Failed("ページが見つかりません(HTTP $code)", networkLevel = false)
        408, in 500..599 -> LinkProbeResult.Failed("サイトが応答しません(HTTP $code)", networkLevel = false)
        else -> LinkProbeResult.Failed("開けません(HTTP $code)", networkLevel = false)
    }

    /** [previous] の状態に [result] を反映した、[now] の時点の新しい状態。「確認しない」の印は引き継ぐ。 */
    fun apply(previous: LinkCheckState?, result: LinkProbeResult, now: Long): LinkCheckState {
        val before = previous ?: LinkCheckState()
        return when (result) {
            LinkProbeResult.Alive -> LinkCheckState(checkedAt = now, muted = before.muted)
            LinkProbeResult.NotCheckable -> before.copy(checkedAt = now)
            is LinkProbeResult.Failed -> {
                val count = before.failureCount + 1
                val first = if (before.failureCount == 0) now else before.firstFailedAt ?: now
                val dead = before.deadAt ?: now.takeIf { count >= DEAD_AFTER_FAILURES && now - first >= DEAD_AFTER_MILLIS }
                LinkCheckState(
                    checkedAt = now, failureCount = count, firstFailedAt = first, deadAt = dead,
                    lastReason = result.reason, muted = before.muted
                )
            }
        }
    }

    /** 今夜確認するべきか。「確認しない」は確認しない。確認前は確認する。そのほかは状態に応じた間隔があいたら */
    fun isDue(state: LinkCheckState?, now: Long): Boolean {
        if (state == null) return true
        if (state.muted) return false
        val interval = when {
            state.isDead -> DEAD_RECHECK_MILLIS
            state.failureCount > 0 -> FAILING_RECHECK_MILLIS
            else -> ALIVE_RECHECK_MILLIS
        }
        return now - state.checkedAt >= interval
    }

    /**
     * 今夜確認する候補を選ぶ。確認の時期が来ているもののうち、失敗が続いているもの(早く確かめる)→ 確認前 → 長く確認していないもの、の順。
     * 同じサイトは [maxPerHost] 件まで、全体で [maxTotal] 件まで。選んだものは、サイトが続けて並ばないよう
     * サイトごとに順番に混ぜる(同じサイトへの間隔を保ちやすくする)。
     */
    fun selectDue(
        candidates: List<LinkCandidate>,
        now: Long,
        maxTotal: Int = MAX_PER_RUN,
        maxPerHost: Int = MAX_PER_HOST_PER_RUN
    ): List<LinkCandidate> {
        val due = candidates
            .filter { isHttpUrl(it.url) && isDue(it.state, now) }
            .sortedWith(
                compareByDescending<LinkCandidate> { (it.state?.failureCount ?: 0) > 0 }
                    .thenBy { it.state?.checkedAt ?: -1L }
                    .thenBy { it.bookmarkId }
            )
        val perHost = HashMap<String, Int>()
        val limited = ArrayList<LinkCandidate>()
        for (candidate in due) {
            if (limited.size >= maxTotal) break
            val host = hostOf(candidate.url)
            val count = perHost.getOrDefault(host, 0)
            if (count >= maxPerHost) continue
            perHost[host] = count + 1
            limited += candidate
        }
        return interleaveByHost(limited)
    }

    /** サイトごとの列を順に1件ずつ取って並べ直す(サイトの最初の登場順を保つ) */
    internal fun interleaveByHost(items: List<LinkCandidate>): List<LinkCandidate> {
        val queues = LinkedHashMap<String, ArrayDeque<LinkCandidate>>()
        items.forEach { queues.getOrPut(hostOf(it.url)) { ArrayDeque() }.addLast(it) }
        val result = ArrayList<LinkCandidate>(items.size)
        while (queues.isNotEmpty()) {
            val iterator = queues.entries.iterator()
            while (iterator.hasNext()) {
                val queue = iterator.next().value
                result += queue.removeFirst()
                if (queue.isEmpty()) iterator.remove()
            }
        }
        return result
    }

    /** URL のホスト(小文字。取れなければ空文字)。サイトごとの間隔・件数の上限に使う */
    fun hostOf(url: String): String =
        runCatching { URI(url.trim()).host }.getOrNull()?.lowercase(Locale.ROOT).orEmpty()

    fun isHttpUrl(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)
    }
}
