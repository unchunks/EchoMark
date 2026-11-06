package com.unchunks.echomark.domain.linkcheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class LinkCheckPolicyTest {

    private val day = TimeUnit.DAYS.toMillis(1)
    private val t0 = 1_000_000_000_000L
    private val notFound = LinkProbeResult.Failed("ページが見つかりません(HTTP 404)", networkLevel = false)

    private fun fail(state: LinkCheckState?, now: Long, result: LinkProbeResult = notFound) = LinkCheckPolicy.apply(state, result, now)

    // ---- HTTP の応答の見分け ----

    @Test
    fun 開けた応答と_開かせてくれないだけの応答は生きているとみなす() {
        listOf(200, 204, 301, 302, 399, 401, 403, 405, 406, 429, 451, 999).forEach {
            assertEquals("HTTP $it", LinkProbeResult.Alive, LinkCheckPolicy.classifyStatus(it))
        }
    }

    @Test
    fun ページが無い_サイトの不具合_そのほかの4xxは失敗() {
        listOf(404, 410, 400, 408, 414, 500, 502, 503, 599).forEach {
            val result = LinkCheckPolicy.classifyStatus(it)
            assertTrue("HTTP $it", result is LinkProbeResult.Failed)
            assertFalse((result as LinkProbeResult.Failed).networkLevel)
            assertTrue(result.reason.contains("HTTP $it"))
        }
    }

    // ---- 1回の失敗では切れたことにしない ----

    @Test
    fun 一回の失敗ではリンク切れにしない() {
        val state = fail(null, t0)

        assertEquals(1, state.failureCount)
        assertEquals(t0, state.firstFailedAt)
        assertNull(state.deadAt)
        assertFalse(state.isDead)
        assertEquals("ページが見つかりません(HTTP 404)", state.lastReason)
    }

    @Test
    fun 三回続けて失敗し_2日以上たったときに初めてリンク切れにする() {
        val first = fail(null, t0)
        val second = fail(first, t0 + day)
        assertFalse(second.isDead)
        val third = fail(second, t0 + 2 * day)

        assertEquals(3, third.failureCount)
        assertEquals(t0, third.firstFailedAt)
        assertEquals(t0 + 2 * day, third.deadAt)
    }

    @Test
    fun 三回失敗しても_最初の失敗から2日たっていなければ切れたことにしない() {
        // 手動の「もう一度確認」を続けて押した場合など
        val state = fail(fail(fail(null, t0), t0 + 1_000), t0 + 2_000)

        assertEquals(3, state.failureCount)
        assertNull(state.deadAt)
    }

    @Test
    fun 日数がたっても_失敗が二回ならまだ切れたことにしない() {
        val state = fail(fail(null, t0), t0 + 3 * day)

        assertEquals(2, state.failureCount)
        assertNull(state.deadAt)
    }

    @Test
    fun 間に一回でも開けたら失敗の記録は消える() {
        val failing = fail(fail(null, t0), t0 + day)

        val recovered = LinkCheckPolicy.apply(failing, LinkProbeResult.Alive, t0 + 2 * day)
        assertEquals(LinkCheckState(checkedAt = t0 + 2 * day), recovered)

        // 次に失敗しても、1回目から数え直す
        val again = fail(recovered, t0 + 3 * day)
        assertEquals(1, again.failureCount)
        assertEquals(t0 + 3 * day, again.firstFailedAt)
        assertNull(again.deadAt)
    }

    @Test
    fun 切れたリンクが開けたら切れの印は消え_切れたままの間は印を保つ() {
        val dead = fail(fail(fail(null, t0), t0 + day), t0 + 2 * day)
        assertTrue(dead.isDead)

        val stillDead = fail(dead, t0 + 9 * day)
        assertEquals(dead.deadAt, stillDead.deadAt)
        assertEquals(4, stillDead.failureCount)

        val alive = LinkCheckPolicy.apply(stillDead, LinkProbeResult.Alive, t0 + 16 * day)
        assertFalse(alive.isDead)
        assertEquals(0, alive.failureCount)
    }

    @Test
    fun 確認の対象にできないものは失敗にも成功にも数えず_確認した日時だけ進める() {
        val failing = fail(null, t0)

        val result = LinkCheckPolicy.apply(failing, LinkProbeResult.NotCheckable, t0 + day)

        assertEquals(failing.copy(checkedAt = t0 + day), result)
        assertEquals(LinkCheckState(checkedAt = t0), LinkCheckPolicy.apply(null, LinkProbeResult.NotCheckable, t0))
    }

    @Test
    fun 確認しないの印は結果が変わっても引き継ぐ() {
        val muted = LinkCheckState(muted = true)

        assertTrue(LinkCheckPolicy.apply(muted, LinkProbeResult.Alive, t0).muted)
        assertTrue(fail(muted, t0).muted)
        assertTrue(LinkCheckPolicy.apply(muted, LinkProbeResult.NotCheckable, t0).muted)
    }

    // ---- いつ確認するか ----

    @Test
    fun 確認の時期は状態ごとの間隔で決まる() {
        assertTrue(LinkCheckPolicy.isDue(null, t0))
        val alive = LinkCheckState(checkedAt = t0)
        assertFalse(LinkCheckPolicy.isDue(alive, t0 + 13 * day))
        assertTrue(LinkCheckPolicy.isDue(alive, t0 + 14 * day))

        val failing = fail(null, t0)
        assertFalse(LinkCheckPolicy.isDue(failing, t0 + 19 * 60 * 60 * 1000))
        assertTrue(LinkCheckPolicy.isDue(failing, t0 + 20 * 60 * 60 * 1000))

        val dead = fail(fail(fail(null, t0), t0 + day), t0 + 2 * day)
        assertFalse(LinkCheckPolicy.isDue(dead, t0 + 8 * day))
        assertTrue(LinkCheckPolicy.isDue(dead, t0 + 9 * day))
    }

    @Test
    fun 確認しないリンクは確認の時期が来ない() {
        assertFalse(LinkCheckPolicy.isDue(LinkCheckState(checkedAt = 0, muted = true), t0 + 100 * day))
    }

    @Test
    fun 夜の確認は失敗が続くものを先に_次に確認前_次に古い順() {
        val candidates = listOf(
            LinkCandidate(1, "https://a.example/1", LinkCheckState(checkedAt = t0 - 30 * day)),
            LinkCandidate(2, "https://b.example/2", null),
            LinkCandidate(3, "https://c.example/3", LinkCheckState(checkedAt = t0 - 40 * day)),
            LinkCandidate(4, "https://d.example/4", LinkCheckState(checkedAt = t0 - 2 * day, failureCount = 1, firstFailedAt = t0 - 2 * day)),
            LinkCandidate(5, "https://e.example/5", LinkCheckState(checkedAt = t0 - day)) // 時期が来ていない
        )

        val selected = LinkCheckPolicy.selectDue(candidates, t0)

        assertEquals(listOf(4L, 2L, 3L, 1L), selected.map { it.bookmarkId })
    }

    @Test
    fun 夜の確認はhttp以外と確認しないリンクを選ばず_全体の上限を守る() {
        val candidates = listOf(
            LinkCandidate(1, "content://media/1"),
            LinkCandidate(2, "ftp://example.com/x"),
            LinkCandidate(3, "https://muted.example/", LinkCheckState(muted = true)),
            LinkCandidate(4, "https://a.example/4"),
            LinkCandidate(5, "http://b.example/5"),
            LinkCandidate(6, "https://c.example/6")
        )

        assertEquals(listOf(4L, 5L), LinkCheckPolicy.selectDue(candidates, t0, maxTotal = 2).map { it.bookmarkId })
    }

    @Test
    fun 同じサイトは一晩に上限まで_残りは次の夜に回す() {
        val candidates = (1L..8L).map { LinkCandidate(it, "https://same.example/$it") } +
            LinkCandidate(20, "https://other.example/x")

        val selected = LinkCheckPolicy.selectDue(candidates, t0, maxPerHost = 3)

        assertEquals(3, selected.count { LinkCheckPolicy.hostOf(it.url) == "same.example" })
        assertEquals(1, selected.count { LinkCheckPolicy.hostOf(it.url) == "other.example" })
    }

    @Test
    fun 選んだリンクは同じサイトが続けて並ばないよう混ぜる() {
        val candidates = listOf(
            LinkCandidate(1, "https://a.example/1"), LinkCandidate(2, "https://a.example/2"),
            LinkCandidate(3, "https://b.example/3"), LinkCandidate(4, "https://a.example/4"),
            LinkCandidate(5, "https://b.example/5")
        )

        val selected = LinkCheckPolicy.selectDue(candidates, t0)

        assertEquals(listOf("a.example", "b.example", "a.example", "b.example", "a.example"), selected.map { LinkCheckPolicy.hostOf(it.url) })
    }

    @Test
    fun ホストは小文字で取り_取れなければ空() {
        assertEquals("example.com", LinkCheckPolicy.hostOf("https://EXAMPLE.com/a"))
        assertEquals("", LinkCheckPolicy.hostOf("not a url"))
    }
}
