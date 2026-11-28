package com.unchunks.echomark.data.help

import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.help.AppAction
import com.unchunks.echomark.domain.help.ProcessingOption
import com.unchunks.echomark.domain.model.SummaryLanguage
import com.unchunks.echomark.domain.repository.DesignStyle
import com.unchunks.echomark.domain.repository.ThemeMode
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.FakeOfflineModeController
import com.unchunks.echomark.testing.FakeProcessingScheduleController
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppActionRunnerImplTest {

    private val bookmarks = FakeBookmarkRepository()
    private val settings = FakeAppSettingsRepository()
    private val offline = FakeOfflineModeController(settings)
    private val schedule = FakeProcessingScheduleController()
    private val runner = AppActionRunnerImpl(bookmarks, settings, offline, schedule)

    @Test
    fun リンクを保存する() = runTest {
        val message = runner.run(AppAction.SaveLink("https://example.com/a"))

        assertEquals("https://example.com/a", bookmarks.savedUrls.single().first)
        assertTrue(message, message.contains("保存しました"))
    }

    @Test
    fun メモは1行目をタイトルにして読んだものとして保存する() = runTest {
        runner.run(AppAction.SaveNote("\n買い物\n牛乳と卵"))

        val saved = bookmarks.bookmarks.value.single()
        assertEquals(BookmarkType.TEXT, saved.type)
        assertEquals("買い物", saved.title)
        assertEquals("\n買い物\n牛乳と卵", saved.content)
        assertTrue(saved.readAt != null)
    }

    @Test
    fun テーマとデザインを切り替える() = runTest {
        assertEquals("テーマをダークにしました", runner.run(AppAction.SetTheme(ThemeMode.DARK)))
        assertEquals(ThemeMode.DARK, settings.themeModeFlow.value)

        runner.run(AppAction.SetDesign(DesignStyle.PAPER))
        assertEquals(DesignStyle.PAPER, settings.designStyleFlow.value)
        assertTrue(settings.designIntroSeenFlow.value)
    }

    @Test
    fun オフラインモードは設定画面と同じ経路で切り替える() = runTest {
        runner.run(AppAction.SetOfflineMode(true))

        assertEquals(listOf(true), offline.calls)
    }

    @Test
    fun 処理の設定を変えたら予定に反映する() = runTest {
        val message = runner.run(AppAction.SetProcessingOption(ProcessingOption.NIGHTLY_BATCH, true))

        assertTrue(settings.processingFlow.value.nightlyBatch)
        assertEquals(false, schedule.changes.single().first.nightlyBatch)
        assertEquals(true, schedule.changes.single().second.nightlyBatch)
        assertEquals("「夜間にまとめて処理する」をオンにしました", message)
    }

    @Test
    fun 要約の言語を変える() = runTest {
        runner.run(AppAction.SetSummaryLanguage(SummaryLanguage.ENGLISH))

        assertEquals(SummaryLanguage.ENGLISH, settings.summaryStyleFlow.value.language)
    }
}
