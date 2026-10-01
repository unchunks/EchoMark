package com.unchunks.echomark.ui.navigation

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Intent の extra を読むため Robolectric で動かす。 */
@RunWith(AndroidJUnit4::class)
class LaunchTargetTest {

    private fun intentWith(value: String?) = Intent(Intent.ACTION_VIEW).apply {
        if (value != null) putExtra(LaunchTarget.EXTRA_KEY, value)
    }

    @Test
    fun ショートカットのextraから開く画面を決める() {
        // res/xml/shortcuts.xml の値と一致していること
        assertEquals(LaunchTarget.NEW_CHAT, LaunchTarget.fromIntent(intentWith("new_chat")))
        assertEquals(LaunchTarget.AI_SETTINGS, LaunchTarget.fromIntent(intentWith("ai_settings")))
        assertEquals(LaunchTarget.ADD_BOOKMARK, LaunchTarget.fromIntent(intentWith("add_bookmark")))
        assertEquals(LaunchTarget.SEARCH, LaunchTarget.fromIntent(intentWith("search")))
    }

    @Test
    fun putIntoで付けたextraはfromIntentで読み戻せる() {
        LaunchTarget.entries.forEach { target ->
            val intent = LaunchTarget.putInto(Intent(Intent.ACTION_VIEW), target)
            assertEquals(target, LaunchTarget.fromIntent(intent))
        }
    }

    @Test
    fun extraが無い_未知の値なら何も開かない() {
        assertNull(LaunchTarget.fromIntent(intentWith(null)))
        assertNull(LaunchTarget.fromIntent(intentWith("unknown")))
        assertNull(LaunchTarget.fromIntent(null))
    }
}
