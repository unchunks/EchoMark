package com.unchunks.echomark.di

import com.unchunks.components.applock.AppLockManager
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.domain.lock.toPolicy
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.map
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppLockModule {

    /**
     * アプリのロック(#100)の、ただ1つの持ち主(部品の app-lock)。設定([AppSettingsRepository.appLock])を読み、
     * `EchoMarkApplication` が起動時に install する。画面は `ui/lock/AppLockGate.kt` がこの状態に従って中身を隠す。
     */
    @Provides
    @Singleton
    fun provideAppLockManager(appSettings: AppSettingsRepository, dispatcherProvider: DispatcherProvider): AppLockManager =
        AppLockManager(appSettings.appLock.map { it.toPolicy() }, dispatcherProvider)
}
