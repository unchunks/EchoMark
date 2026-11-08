package com.unchunks.echomark.worker

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.ProcessingSettings
import dagger.Lazy
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject

/**
 * 夜間にまとめて処理する設定(#9)のとき、昼の間に保存して処理待ちのまま夜を待っていたブックマークを、
 * 充電中を条件にした 1 件ずつの列に積む。
 */
class NightlyProcessingTask @Inject constructor(
    private val appSettings: AppSettingsRepository,
    private val bookmarkRepository: Lazy<BookmarkRepository>
) : NightlyTask {
    override val name: String = "bookmark_processing"

    override suspend fun run() {
        if (!appSettings.processingSettings.first().nightlyBatch) return
        val count = bookmarkRepository.get().enqueueWaitingProcessing(nightly = true)
        if (count > 0) Timber.i("夜間の処理を積んだ: %d 件", count)
    }
}

/**
 * 処理の設定を WorkManager の予定と待っている処理に反映する。設定画面から使い、ViewModel のテストでは Fake に差し替える。
 */
interface ProcessingScheduleController {
    /** アプリの起動時に、夜間の仕事の予定を登録する(登録済みならそのまま) */
    fun ensureScheduled(settings: ProcessingSettings)

    /** 設定を変えたときに反映する。夜間の時刻を変えたら予定を登録し直し、夜間の処理をやめたら待っていたものをすぐ積む */
    suspend fun onSettingsChanged(previous: ProcessingSettings, current: ProcessingSettings)
}

class WorkManagerProcessingScheduleController @Inject constructor(
    private val workManager: WorkManager,
    private val bookmarkRepository: Lazy<BookmarkRepository>
) : ProcessingScheduleController {

    override fun ensureScheduled(settings: ProcessingSettings) {
        NightlyTaskScheduler.schedule(workManager, settings.nightlyHour, ExistingPeriodicWorkPolicy.KEEP)
    }

    override suspend fun onSettingsChanged(previous: ProcessingSettings, current: ProcessingSettings) {
        if (previous.nightlyHour != current.nightlyHour) {
            NightlyTaskScheduler.schedule(workManager, current.nightlyHour, ExistingPeriodicWorkPolicy.UPDATE)
        }
        if (previous.nightlyBatch && !current.nightlyBatch) {
            bookmarkRepository.get().enqueueWaitingProcessing(nightly = false)
        }
    }
}
