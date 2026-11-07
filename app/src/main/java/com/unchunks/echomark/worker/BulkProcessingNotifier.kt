package com.unchunks.echomark.worker

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.MainActivity
import com.unchunks.echomark.R
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.ui.navigation.Routes
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * まとめての処理(一括の再処理・夜間の処理)が動いている間、「まとめて処理中 3 / 20 件」の通知を出す(#66)。
 * 通知から処理の状況の画面を開け、「やめる」で列を止められる。列が終わったら通知を消す。
 * 通知の権限が無ければ出さない(処理はそのまま続く)。
 */
@Singleton
class BulkProcessingNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val workScheduler: Lazy<BookmarkWorkScheduler>,
    dispatcherProvider: DispatcherProvider
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)

    /** 列の進み具合を見て通知を出し直す(Application.onCreate から 1 回呼ぶ)。 */
    fun start() {
        scope.launch {
            try {
                workScheduler.get().observeBulkProgress().collect { progress ->
                    if (progress == null || progress.total <= 0) cancel() else show(progress)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "まとめての処理の通知を出せない")
            }
        }
    }

    private fun show(progress: BookmarkWorkScheduler.BulkProgress) {
        if (!canNotify()) return
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification(context, progress))
        } catch (e: SecurityException) {
            Timber.w(e, "通知の権限が無い")
        }
    }

    private fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun canNotify(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val NOTIFICATION_ID = 3003

        /** まとめての処理の通知。チャンネルは AI 処理の通知と同じ(低い重要度、音なし) */
        internal fun notification(context: Context, progress: BookmarkWorkScheduler.BulkProgress): Notification {
            AiProcessingNotification.ensureChannel(context)
            val open = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                Intent(Intent.ACTION_VIEW, Routes.PROCESSING_QUEUE_DEEP_LINK.toUri(), context, MainActivity::class.java)
                    // 再発見の通知と同じく、作り直した画面の上で Navigation のディープリンクとして開く
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val cancel = PendingIntent.getBroadcast(
                context,
                NOTIFICATION_ID,
                Intent(context, BulkProcessingCancelReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, AiProcessingNotification.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.bulk_processing_notification_title))
                .setContentText(
                    context.getString(R.string.bulk_processing_notification_text, progress.done, progress.total)
                )
                .setProgress(progress.total, progress.done, false)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setContentIntent(open)
                .addAction(0, context.getString(R.string.bulk_processing_notification_cancel), cancel)
                .build()
        }
    }
}

/** まとめての処理の通知の「やめる」。列を止め、列にあったものの処理をやめる。 */
@AndroidEntryPoint
class BulkProcessingCancelReceiver : BroadcastReceiver() {
    @Inject lateinit var bookmarkRepository: BookmarkRepository
    @Inject lateinit var dispatcherProvider: DispatcherProvider

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + dispatcherProvider.io).launch {
            try {
                val count = bookmarkRepository.cancelBulkProcessing()
                Timber.i("まとめての処理をやめた: %d 件", count)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "まとめての処理をやめられない")
            } finally {
                NotificationManagerCompat.from(context).cancel(BulkProcessingNotifier.NOTIFICATION_ID)
                pending.finish()
            }
        }
    }
}
