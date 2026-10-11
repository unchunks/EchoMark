package com.unchunks.echomark.di

import com.unchunks.echomark.worker.RediscoverScheduleController
import com.unchunks.echomark.worker.WorkManagerRediscoverScheduleController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SchedulerModule {

    @Binds
    abstract fun bindRediscoverScheduleController(
        impl: WorkManagerRediscoverScheduleController
    ): RediscoverScheduleController
}
