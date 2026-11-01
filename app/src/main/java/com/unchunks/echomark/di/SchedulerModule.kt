package com.unchunks.echomark.di

import com.unchunks.echomark.worker.FeedCheckNightlyTask
import com.unchunks.echomark.worker.NightlyProcessingTask
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
}
