package com.unchunks.echomark.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.unchunks.echomark.EchoMarkApplication
import com.unchunks.echomark.MainActivity
import com.unchunks.echomark.R
import com.unchunks.echomark.domain.rediscover.RediscoverSelector
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.ui.navigation.Routes
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 30日以上開いていないブックマークを最大3件、週1回通知する。
 * 通知タップで先頭のブックマークの詳細画面をディープリンクで開く。
 */
@HiltWorker
class RediscoverDigestWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val bookmarkRepository: BookmarkRepository,
    private val appSettings: AppSettingsRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!appSettings.rediscoverSettings.first().enabled) return Result.success()

        // API 31/32 は実行時権限が無いため、権限ではなく「通知が有効か」で判定する
        // (API 33+ では POST_NOTIFICATIONS が未許可のときも false になる)
        val notificationManager = NotificationManagerCompat.from(context)
        if (!notificationManager.areNotificationsEnabled()) {
            Timber.i("通知が無効のため再発見ダイジェストをスキップ")
            return Result.success()
        }

        val now = System.currentTimeMillis()
        val threshold = now - TimeUnit.DAYS.toMillis(RediscoverSelector.STALE_DAYS)
        val candidates = bookmarkRepository.getStaleBookmarks(threshold, CANDIDATE_LIMIT)
        val picked = RediscoverSelector.select(
            candidates = candidates,
            notifiedAt = appSettings.getRediscoverNotified(),
            now = now
        )
        if (picked.isEmpty()) return Result.success()

        val first = picked.first()
        val tapIntent = Intent(
            Intent.ACTION_VIEW,
            Routes.bookmarkDeepLink(first.id).toUri(),
            context,
            MainActivity::class.java
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val contentIntent = PendingIntent.getActivity(
            context,
            first.id.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val titles = picked.joinToString("\n") { "・${it.title}" }
        val notification = NotificationCompat.Builder(context, EchoMarkApplication.REDISCOVER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.rediscover_notification_title))
            .setContentText(first.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(titles))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // 権限が実行中に取り消された場合など。通知済みとは記録しない
            Timber.w(e, "通知の表示に失敗")
            return Result.success()
        }
        appSettings.recordRediscoverNotified(picked.map { it.id }, now)
        return Result.success()
    }

    companion object {
        private const val NOTIFICATION_ID = 2001

        // 通知済みで除外される分を見越して多めに取得する
        private const val CANDIDATE_LIMIT = 50
    }
}
