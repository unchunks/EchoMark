package com.unchunks.echomark.ui.navigation

import android.content.Intent

/**
 * 起動直後に開く画面。アプリショートカット(res/xml/shortcuts.xml)、ホーム画面ウィジェット、オンボーディングの続きで使う。
 * [intentValue] は shortcuts.xml の extra の値と一致させること。
 */
enum class LaunchTarget(val intentValue: String) {
    /** 一覧を開いて追加シートを出す(クリップボードに URL があれば貼り付けの提案が出る)。 */
    ADD_BOOKMARK("add_bookmark"),

    /** 一覧を開いて検索モードにする。 */
    SEARCH("search"),
    NEW_CHAT("new_chat"),
    AI_SETTINGS("ai_settings");

    companion object {
        /** ショートカットの Intent に付ける extra のキー。 */
        const val EXTRA_KEY = "com.unchunks.echomark.extra.LAUNCH_TARGET"

        /** [target] を開く Intent に extra を付ける(ウィジェットなどアプリ内から起動するとき)。 */
        fun putInto(intent: Intent, target: LaunchTarget): Intent = intent.putExtra(EXTRA_KEY, target.intentValue)

        fun fromIntent(intent: Intent?): LaunchTarget? {
            val value = intent?.getStringExtra(EXTRA_KEY) ?: return null
            return entries.firstOrNull { it.intentValue == value }
        }
    }
}
