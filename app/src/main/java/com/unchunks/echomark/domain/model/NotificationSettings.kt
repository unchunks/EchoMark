package com.unchunks.echomark.domain.model

data class NotificationSettings(
    val enabled: Boolean = true,
    val hour: Int = 20,
    val minute: Int = 0
)
