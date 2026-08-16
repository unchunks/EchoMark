package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.model.NotificationSettings
import kotlinx.coroutines.flow.Flow

interface NotificationSettingsRepository {
    val settingsFlow: Flow<NotificationSettings>
    suspend fun setEnabled(enabled: Boolean)
    suspend fun setTime(hour: Int, minute: Int)
}
