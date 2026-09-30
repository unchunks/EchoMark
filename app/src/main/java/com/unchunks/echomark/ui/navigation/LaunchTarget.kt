package com.unchunks.echomark.ui.navigation

import android.content.Intent

/**
 * 起動直後に開く画面。アプリショートカット(res/xml/shortcuts.xml)と、オンボーディングの続きで使う。
 * [intentValue] は shortcuts.xml の extra の値と一致させること。
 */
enum class LaunchTarget(val intentValue: String) {
    NEW_CHAT("new_chat"),
    AI_SETTINGS("ai_settings");

    companion object {
        /** ショートカットの Intent に付ける extra のキー。 */
        const val EXTRA_KEY = "com.unchunks.echomark.extra.LAUNCH_TARGET"

        fun fromIntent(intent: Intent?): LaunchTarget? {
            val value = intent?.getStringExtra(EXTRA_KEY) ?: return null
            return entries.firstOrNull { it.intentValue == value }
        }
    }
}
