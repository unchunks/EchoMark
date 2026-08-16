package com.unchunks.echomark.domain.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.unchunks.echomark.domain.model.NotificationSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class NotificationSettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : NotificationSettingsRepository {

    override val settingsFlow: Flow<NotificationSettings> = dataStore.data.map { prefs ->
        NotificationSettings(
            enabled = prefs[KEY_ENABLED] ?: true,
            hour = prefs[KEY_HOUR] ?: 20,
            minute = prefs[KEY_MINUTE] ?: 0
        )
    }

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_ENABLED] = enabled }
    }

    override suspend fun setTime(hour: Int, minute: Int) {
        dataStore.edit {
            it[KEY_HOUR] = hour
            it[KEY_MINUTE] = minute
        }
    }

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("review_notification_enabled")
        private val KEY_HOUR = intPreferencesKey("review_notification_hour")
        private val KEY_MINUTE = intPreferencesKey("review_notification_minute")
    }
}