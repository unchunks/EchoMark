package com.unchunks.echomark.data.help

import com.unchunks.echomark.data.network.OfflineModeController
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.help.AppAction
import com.unchunks.echomark.domain.help.AppActionRunner
import com.unchunks.echomark.domain.help.ProcessingOption
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.ProcessingSettings
import com.unchunks.echomark.worker.ProcessingScheduleController
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * 使い方の AI から頼まれた操作を、設定画面・一覧と同じ経路で実行する
 * (保存は AI 処理の登録まで、処理の設定は夜間の予定の登録し直しまで、オフラインモードは処理の積み直しまで行う)。
 */
class AppActionRunnerImpl @Inject constructor(
    private val bookmarks: BookmarkRepository,
    private val appSettings: AppSettingsRepository,
    private val offlineModeController: OfflineModeController,
    private val processingScheduleController: ProcessingScheduleController
) : AppActionRunner {

    override suspend fun run(action: AppAction): String = when (action) {
        is AppAction.SaveLink -> {
            val result = bookmarks.saveUrlBookmark(action.url, title = null, memo = null)
            if (result.isDuplicate) "このリンクは保存済みです" else "リンクを保存しました。要約とタグ付けを始めます"
        }
        is AppAction.SaveNote -> {
            val now = System.currentTimeMillis()
            bookmarks.saveBookmark(
                Bookmark(
                    type = BookmarkType.TEXT,
                    content = action.text,
                    title = noteTitle(action.text),
                    createdAt = now,
                    lastAccessedAt = now,
                    // 自分で書いたメモは、読んだものとして始める(一覧の追加シートと同じ)
                    readAt = now
                )
            )
            "メモを保存しました"
        }
        is AppAction.SetTheme -> {
            appSettings.setThemeMode(action.mode)
            action.label.removeSuffix("する") + "しました"
        }
        is AppAction.SetDesign -> {
            appSettings.setDesignStyle(action.style)
            // 自分で選んだ人には、一覧の新しいデザインの案内を出さない(設定画面と同じ)
            appSettings.setDesignIntroSeen()
            action.label.removeSuffix("する") + "しました"
        }
        is AppAction.SetOfflineMode -> {
            offlineModeController.setOfflineMode(action.enabled)
            action.label.removeSuffix("する") + "しました"
        }
        is AppAction.SetProcessingOption -> {
            val previous = appSettings.processingSettings.first()
            appSettings.updateProcessingSettings { it.with(action.option, action.enabled) }
            processingScheduleController.onSettingsChanged(previous, appSettings.processingSettings.first())
            action.label.removeSuffix("する") + "しました"
        }
        is AppAction.SetSummaryLanguage -> {
            appSettings.updateSummaryStyle { it.copy(language = action.language) }
            "要約の言語を「${action.language.label}」にしました。次に作る要約から変わります"
        }
        AppAction.ReprocessFailed -> {
            val count = bookmarks.enqueueFailedAndWaitingProcessing()
            if (count == 0) "再処理が必要なブックマークはありません" else "${count} 件のブックマークを再処理します"
        }
        // 画面を開く・検索は画面側で行う
        is AppAction.Open, is AppAction.Search -> error("画面を開く操作は画面側で扱う: $action")
    }

    private fun ProcessingSettings.with(option: ProcessingOption, enabled: Boolean) = when (option) {
        ProcessingOption.CHARGING_ONLY -> copy(chargingOnly = enabled)
        ProcessingOption.UNMETERED_ONLY -> copy(unmeteredOnly = enabled)
        ProcessingOption.NIGHTLY_BATCH -> copy(nightlyBatch = enabled)
    }

    /** メモのタイトル。1行目を、長ければ切って使う(追加シートでタイトルを空にしたときと同じ考え方) */
    private fun noteTitle(text: String): String {
        val firstLine = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "メモ"
        return if (firstLine.length <= MAX_TITLE_CHARS) firstLine else firstLine.take(MAX_TITLE_CHARS - 1) + "…"
    }

    private companion object {
        const val MAX_TITLE_CHARS = 40
    }
}
