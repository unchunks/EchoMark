package com.unchunks.echomark

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.repository.NotificationSettingsRepository
import com.unchunks.echomark.worker.ReembedAllWorker
import com.unchunks.echomark.worker.ReviewDigestScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class EchoMarkApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var workManager: WorkManager
    @Inject lateinit var settingsRepository: NotificationSettingsRepository
    @Inject lateinit var dispatcherProvider: DispatcherProvider

    private val applicationScope = CoroutineScope(SupervisorJob())

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        applicationScope.launch(dispatcherProvider.io) {
            val settings = settingsRepository.settingsFlow.first()
            if (settings.enabled) {
                ReviewDigestScheduler.schedule(workManager, settings.hour, settings.minute, ExistingPeriodicWorkPolicy.KEEP)
            }
        }

        workManager.enqueueUniqueWork(
            ReembedAllWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReembedAllWorker>().build()
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            REVIEW_CHANNEL_ID, "復習リマインダー", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "保存した学習内容の復習タイミングをお知らせします" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val REVIEW_CHANNEL_ID = "review_reminder_channel"
    }
}
