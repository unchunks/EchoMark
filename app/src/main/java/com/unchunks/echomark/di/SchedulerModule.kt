package com.unchunks.echomark.di

import com.unchunks.echomark.worker.FeedCheckNightlyTask
import com.unchunks.echomark.worker.LinkCheckNightlyTask
import com.unchunks.echomark.data.backup.AutoBackupNotifier
import com.unchunks.echomark.data.backup.BackupFolder
import com.unchunks.echomark.data.backup.SafBackupFolder
import com.unchunks.echomark.worker.AutoBackupController
import com.unchunks.echomark.worker.AutoBackupNightlyTask
import com.unchunks.echomark.worker.NightlyProcessingTask
import com.unchunks.echomark.worker.NotificationAutoBackupNotifier
import com.unchunks.echomark.worker.WorkManagerAutoBackupController
import com.unchunks.echomark.worker.NightlyTask
import com.unchunks.echomark.worker.ProcessingScheduleController
import com.unchunks.echomark.worker.RediscoverScheduleController
import com.unchunks.echomark.worker.WorkManagerProcessingScheduleController
import com.unchunks.echomark.worker.WorkManagerRediscoverScheduleController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class SchedulerModule {

    @Binds
    abstract fun bindRediscoverScheduleController(
        impl: WorkManagerRediscoverScheduleController
    ): RediscoverScheduleController

    @Binds
    abstract fun bindProcessingScheduleController(
        impl: WorkManagerProcessingScheduleController
    ): ProcessingScheduleController

    /** 夜間の仕事(NightlyTaskWorker が毎晩実行する)。新しい仕事はここに @IntoSet で足す */
    @Binds
    @IntoSet
    abstract fun bindNightlyProcessingTask(impl: NightlyProcessingTask): NightlyTask

    /** 購読しているフィードの確認(#78)。充電中・Wi-Fi の設定を条件にしたワークとして積む */
    @Binds
    @IntoSet
    abstract fun bindFeedCheckNightlyTask(impl: FeedCheckNightlyTask): NightlyTask

    /** リンク切れの確認(#94)。充電中・Wi-Fi の設定を条件にしたワークとして積む */
    @Binds
    @IntoSet
    abstract fun bindLinkCheckNightlyTask(impl: LinkCheckNightlyTask): NightlyTask

    /** 自動バックアップ。処理の設定の「充電中だけ」を条件にしたワークとして積む */
    @Binds
    @IntoSet
    abstract fun bindAutoBackupNightlyTask(impl: AutoBackupNightlyTask): NightlyTask

    @Binds
    abstract fun bindAutoBackupController(impl: WorkManagerAutoBackupController): AutoBackupController

    @Binds
    abstract fun bindAutoBackupNotifier(impl: NotificationAutoBackupNotifier): AutoBackupNotifier

    @Binds
    abstract fun bindBackupFolder(impl: SafBackupFolder): BackupFolder
}
