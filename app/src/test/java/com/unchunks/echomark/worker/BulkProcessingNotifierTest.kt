package com.unchunks.echomark.worker

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** まとめての処理の通知(#66)の内容。 */
@RunWith(AndroidJUnit4::class)
class BulkProcessingNotifierTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun notification(done: Int, total: Int): Notification =
        BulkProcessingNotifier.notification(context, BookmarkWorkScheduler.BulkProgress(done, total))

    @Test
    fun 進み具合と全体の件数を出す() {
        val notification = notification(done = 3, total = 20)

        val extras = notification.extras
        assertEquals("まとめて処理中", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("3 / 20 件", extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(20, extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(3, extras.getInt(Notification.EXTRA_PROGRESS))
        assertTrue(extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE).not())
    }

    @Test
    fun 消せない通知で_タップで処理の状況を開き_やめるを添える() {
        val notification = notification(done = 0, total = 5)

        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(listOf("やめる"), notification.actions.map { it.title.toString() })
        assertTrue(notification.contentIntent != null)
    }

    @Test
    fun AI処理の通知と同じ低い重要度のチャンネルを使う() {
        val notification = notification(done = 1, total = 2)

        assertEquals(AiProcessingNotification.CHANNEL_ID, notification.channelId)
        val channel = NotificationManagerCompat.from(context).getNotificationChannelCompat(AiProcessingNotification.CHANNEL_ID)!!
        assertEquals(NotificationManagerCompat.IMPORTANCE_LOW, channel.importance)
    }
}
