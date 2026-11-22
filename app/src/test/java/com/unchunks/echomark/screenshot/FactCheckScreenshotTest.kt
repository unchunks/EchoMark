package com.unchunks.echomark.screenshot

import androidx.compose.material3.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.factcheck.CheckedClaim
import com.unchunks.echomark.domain.factcheck.ClaimVerdict
import com.unchunks.echomark.domain.factcheck.FactCheckEvidence
import com.unchunks.echomark.domain.factcheck.FactCheckProgress
import com.unchunks.echomark.domain.factcheck.FactCheckResult
import com.unchunks.echomark.domain.factcheck.FactCheckTarget
import com.unchunks.echomark.domain.factcheck.FactCheckText
import com.unchunks.echomark.domain.websearch.WebSearchAvailability
import com.unchunks.echomark.ui.factcheck.FactCheckSheetContent
import com.unchunks.echomark.ui.factcheck.FactCheckUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

/** 「Web で確かめる」のシートの見た目(結果・途中・キーが無い・失敗・古い結果)。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FactCheckScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val target = FactCheckTarget.bookmark(1)
    private val text = "Kotlin 2.3 では K2 コンパイラーが既定になりました。リリースは 2026 年 3 月です。"
    private val ready = WebSearchAvailability(keyConfigured = true)

    private val result = FactCheckResult(
        target = target,
        textHash = FactCheckText.hash(text),
        claims = listOf(
            CheckedClaim(
                "Kotlin 2.3 では K2 コンパイラーが既定になった",
                ClaimVerdict.SUPPORTED,
                listOf(
                    FactCheckEvidence(
                        "https://kotlinlang.org/docs/whatsnew23.html", "What's new in Kotlin 2.3", "kotlinlang.org",
                        "Kotlin 2.3 では、K2 コンパイラーが既定になりました。"
                    )
                )
            ),
            CheckedClaim(
                "リリースは 2026 年 3 月",
                ClaimVerdict.CONFLICTING,
                listOf(
                    FactCheckEvidence(
                        "https://blog.example.com/kotlin-23", "Kotlin 2.3 のリリース", "blog.example.com",
                        "Kotlin 2.3 は 2026 年 5 月にリリースされました。"
                    )
                )
            ),
            CheckedClaim(
                "ビルドが 2 倍速くなった",
                ClaimVerdict.NOT_FOUND,
                listOf(FactCheckEvidence("https://news.example.org/k", "Kotlin のニュース", "news.example.org", ""))
            )
        ),
        // 2026-11-15 09:05(日本時間)
        checkedAt = 1_794_701_100_000L
    )

    private fun capture(name: String, state: FactCheckUiState) = screenshot.captureLightDark(name, widthDp = 400) {
        Surface {
            FactCheckSheetContent(state, onCheck = {}, onOpenUrl = {}, onOpenAiSettings = {}, zone = ZoneId.of("Asia/Tokyo"))
        }
    }

    @Test
    fun results() = capture("fact_check_results", FactCheckUiState(target, text, ready, result))

    @Test
    fun running() = capture(
        "fact_check_running",
        FactCheckUiState(target, text, ready, progress = FactCheckProgress.Searching(2, 3))
    )

    @Test
    fun noKey() = capture("fact_check_no_key", FactCheckUiState(target, text, WebSearchAvailability()))

    @Test
    fun staleWithError() = capture(
        "fact_check_stale_error",
        FactCheckUiState(target, "要約を直した", ready, result, error = "今月の Web 検索の上限に達しました。Brave の管理画面で残りを確かめてください")
    )

    @Test
    fun offline() = capture("fact_check_offline", FactCheckUiState(target, text, WebSearchAvailability(keyConfigured = true, offline = true)))
}
