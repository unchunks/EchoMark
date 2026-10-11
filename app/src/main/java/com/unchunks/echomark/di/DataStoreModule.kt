package com.unchunks.echomark.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.unchunks.echomark.di.qualifier.ApiKeyStore
import javax.inject.Singleton

// DataStore は同名ファイルにつき 1 インスタンスにする必要があるため、トップレベルの委譲で保持する
private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")
// ファイル名は backup_rules.xml / data_extraction_rules.xml の除外指定と一致させること
private val Context.apiKeyDataStore: DataStore<Preferences> by preferencesDataStore(name = "api_keys")

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun provideAppSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.appSettingsDataStore

    @Provides
    @Singleton
    @ApiKeyStore
    fun provideApiKeyDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.apiKeyDataStore
}
