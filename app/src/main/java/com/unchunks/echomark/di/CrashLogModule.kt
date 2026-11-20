package com.unchunks.echomark.di

import android.content.Context
import com.unchunks.echomark.crash.CrashLogStore
import com.unchunks.echomark.crash.CrashReporter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object CrashLogModule {
    /** Application が Hilt より先に仕込むハンドラと同じ記録先を使う。 */
    @Provides
    fun provideCrashLogStore(@ApplicationContext context: Context): CrashLogStore = CrashReporter.store(context)
}
