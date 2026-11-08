package com.unchunks.echomark.ui.shortcut

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.R
import com.unchunks.echomark.ui.navigation.LaunchTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * res/xml/shortcuts.xml(ランチャーのアイコン長押しで出るショートカット)を読み、アプリの側の定義と食い違っていないか確かめる。
 * xml の extra の値が [LaunchTarget] に無いと、タップしても何も開かない。
 */
@RunWith(AndroidJUnit4::class)
class ShortcutsXmlTest {

    private data class Shortcut(val id: String, val target: String?, val targetClass: String?, val targetPackage: String?)

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun readShortcuts(): List<Shortcut> {
        val parser = context.resources.getXml(R.xml.shortcuts)
        val android = "http://schemas.android.com/apk/res/android"
        val result = mutableListOf<Shortcut>()
        var id: String? = null
        var target: String? = null
        var targetClass: String? = null
        var targetPackage: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "shortcut" -> {
                        id = parser.getAttributeValue(android, "shortcutId")
                        target = null
                    }
                    "intent" -> {
                        targetClass = parser.getAttributeValue(android, "targetClass")
                        targetPackage = parser.getAttributeValue(android, "targetPackage")
                    }
                    "extra" -> if (parser.getAttributeValue(android, "name") == LaunchTarget.EXTRA_KEY) {
                        target = parser.getAttributeValue(android, "value")
                    }
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "shortcut") {
                result += Shortcut(checkNotNull(id), target, targetClass, targetPackage)
            }
        }
        return result
    }

    @Test
    fun どのショートカットも_LaunchTargetに対応する値を持つ() {
        val shortcuts = readShortcuts()
        assertTrue(shortcuts.isNotEmpty())
        shortcuts.forEach { shortcut ->
            val known = LaunchTarget.entries.map { it.intentValue }
            assertTrue("${shortcut.id} の extra ${shortcut.target} が LaunchTarget に無い", shortcut.target in known)
        }
    }

    @Test
    fun ショートカットの_IDは重ならず_起動先はアプリのMainActivity() {
        val shortcuts = readShortcuts()
        assertEquals(shortcuts.size, shortcuts.map { it.id }.toSet().size)
        shortcuts.forEach {
            assertEquals(context.packageName, it.targetPackage)
            assertEquals("com.unchunks.echomark.MainActivity", it.targetClass)
        }
    }

    @Test
    fun 追加_検索_チャット_未読_AI設定の順で並ぶ() {
        // ランチャーは先頭から数件しか出さない。よく使うものを前にする(AI の設定は最後)
        assertEquals(
            listOf("add_bookmark", "search", "new_chat", "unread", "ai_settings"),
            readShortcuts().map { it.id }
        )
    }

    @Test
    fun ラベルは短い名前と長い名前の両方がある() {
        listOf(
            R.string.shortcut_add_bookmark_short, R.string.shortcut_search_short, R.string.shortcut_new_chat_short,
            R.string.shortcut_unread_short, R.string.shortcut_ai_settings_short
        ).forEach { assertTrue(context.getString(it).length <= 10) }
        assertTrue(context.getString(R.string.shortcut_unread_long).isNotBlank())
    }
}
