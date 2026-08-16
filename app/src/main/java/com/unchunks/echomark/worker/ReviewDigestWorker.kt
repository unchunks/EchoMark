package com.unchunks.echomark.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.unchunks.echomark.EchoMarkApplication
import com.unchunks.echomark.domain.repository.ChatRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ReviewDigestWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val chatRepository: ChatRepository
) : CoroutineWorker(context, params) {

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override suspend fun doWork(): Result {
        val dueItems = chatRepository.getDueLearningItems((System.currentTimeMillis()))
        if (dueItems.isEmpty()) return Result.success()

        val hasPermission = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return Result.success() // 権限が無ければ何もせず正常終了扱い

        val notification = NotificationCompat.Builder(context, EchoMarkApplication.REVIEW_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // TODO: 仮アイコン。後で専用アイコンに差し替え
            .setContentTitle("復習の時間です")
            .setContentText("${dueItems.size}件の学習ノートが復習待ちです")
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(REVIEW_NOTIFICATION_ID, notification)
        return Result.success()
    }

    companion object {
        private const val REVIEW_NOTIFICATION_ID = 1001
    }
}
