package com.unchunks.echomark.data.linkcheck

import com.unchunks.echomark.data.local.dao.BookmarkDao
import com.unchunks.echomark.data.local.dao.LinkCheckDao
import com.unchunks.echomark.data.local.entity.LinkCheckEntity
import com.unchunks.echomark.domain.linkcheck.LinkCandidate
import com.unchunks.echomark.domain.linkcheck.LinkCheckPolicy
import com.unchunks.echomark.domain.linkcheck.LinkCheckState
import com.unchunks.echomark.domain.linkcheck.LinkProbeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import timber.log.Timber
import javax.inject.Inject

/** 夜間の確認の結果。 */
data class LinkCheckSummary(
    /** 確認した件数(通信が切れて記録しなかったものは含めない) */
    val checked: Int = 0,
    /** 開けなかった件数 */
    val failed: Int = 0,
    /** この確認で新しくリンク切れと判断した件数 */
    val newlyDead: Int = 0,
    /** 端末の通信が切れたため、途中でやめた(あとでやり直す) */
    val offline: Boolean = false
)

/**
 * 保存したリンクが今も開けるかを確認して、結果を [LinkCheckEntity] に記録する(#94)。決まりは [LinkCheckPolicy]:
 * - 1晩に [LinkCheckPolicy.MAX_PER_RUN] 件まで。同じサイトへは [LinkCheckPolicy.MAX_PER_HOST_PER_RUN] 件まで、
 *   [LinkCheckPolicy.HOST_INTERVAL_MILLIS] ミリ秒以上あけて確認する(サイトに負担をかけない)
 * - 1回の失敗では切れたことにしない(続けて失敗し、日数もたってから)
 * - 開けなかった原因が端末の通信の切れかもしれないとき([NetworkStatus] で確かめる)は、記録せずに途中でやめる
 * - 「確認しない」を選んだものと、http / https 以外・ローカルネットワークのリンクは確認しない
 */
class LinkChecker @Inject constructor(
    private val bookmarkDao: BookmarkDao,
    private val linkCheckDao: LinkCheckDao,
    private val prober: LinkProber,
    private val network: NetworkStatus
) {
    /** 同じサイトへの間隔をあける待ち。テストでは待たずに記録する */
    internal var sleeper: suspend (Long) -> Unit = { delay(it) }

    /** 今夜確認するべきリンクの数(確認する物があるかの判断に使う。通信はしない) */
    suspend fun countDue(now: Long = System.currentTimeMillis()): Int = LinkCheckPolicy.selectDue(candidates(), now).size

    /** 確認の時期が来ているリンクを、上限まで確認する。 */
    suspend fun checkDue(now: () -> Long = System::currentTimeMillis): LinkCheckSummary {
        val targets = LinkCheckPolicy.selectDue(candidates(), now())
        var checked = 0
        var failed = 0
        var newlyDead = 0
        val lastRequestAt = HashMap<String, Long>()
        for (candidate in targets) {
            // 同じサイトへの間隔をあける(サイトごとに混ぜて並べてあるので、ふつうは待たない)
            val host = LinkCheckPolicy.hostOf(candidate.url)
            lastRequestAt[host]?.let { last ->
                val wait = LinkCheckPolicy.HOST_INTERVAL_MILLIS - (now() - last)
                if (wait > 0) sleeper(wait)
            }
            lastRequestAt[host] = now()

            val result = try {
                prober.probe(candidate.url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "リンクの確認に失敗: %s", candidate.url)
                LinkProbeResult.NotCheckable
            }
            // 通信の失敗が、端末の通信の切れのせいかもしれないときは、記録せずやめる(リンク切れに数えない)
            if (result is LinkProbeResult.Failed && result.networkLevel && !network.isOnline()) {
                Timber.i("端末が通信できないため、リンクの確認を途中でやめた")
                return LinkCheckSummary(checked, failed, newlyDead, offline = true)
            }
            val outcome = record(candidate.bookmarkId, candidate.state, result, now())
            checked++
            if (result is LinkProbeResult.Failed) failed++
            if (outcome.isDead && candidate.state?.isDead != true) newlyDead++
        }
        return LinkCheckSummary(checked, failed, newlyDead)
    }

    /**
     * 1件を今すぐ確認する(詳細画面の「もう一度確認」)。確認の時期や「確認しない」は見ない。
     * 通信できないときは記録せず null。ブックマークが無い・リンクでなければ null。
     */
    suspend fun checkOne(bookmarkId: Long, now: Long = System.currentTimeMillis()): LinkCheckState? {
        val url = bookmarkDao.getById(bookmarkId)?.contentUri?.takeIf { LinkCheckPolicy.isHttpUrl(it) } ?: return null
        val previous = linkCheckDao.get(bookmarkId)?.toState()
        val result = prober.probe(url)
        if (result is LinkProbeResult.Failed && result.networkLevel && !network.isOnline()) return null
        return record(bookmarkId, previous, result, now)
    }

    private suspend fun record(bookmarkId: Long, previous: LinkCheckState?, result: LinkProbeResult, now: Long): LinkCheckState {
        val next = LinkCheckPolicy.apply(previous, result, now)
        // 確認している間にブックマークが削除されたときは、外部キーで失敗する。記録しなくてよい
        if (bookmarkDao.exists(bookmarkId)) linkCheckDao.upsert(next.toEntity(bookmarkId))
        return next
    }

    private suspend fun candidates(): List<LinkCandidate> {
        val states = linkCheckDao.getAll().associate { it.bookmarkId to it.toState() }
        return bookmarkDao.getUrlEntries().map { LinkCandidate(it.id, it.url, states[it.id]) }
    }
}

internal fun LinkCheckEntity.toState() = LinkCheckState(
    checkedAt = checkedAt, failureCount = failureCount, firstFailedAt = firstFailedAt, deadAt = deadAt,
    lastReason = lastReason, muted = muted
)

internal fun LinkCheckState.toEntity(bookmarkId: Long) = LinkCheckEntity(
    bookmarkId = bookmarkId, checkedAt = checkedAt, failureCount = failureCount, firstFailedAt = firstFailedAt,
    deadAt = deadAt, lastReason = lastReason, muted = muted
)
