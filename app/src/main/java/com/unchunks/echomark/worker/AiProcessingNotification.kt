package com.unchunks.echomark.worker

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import com.unchunks.echomark.R
import com.unchunks.echomark.data.ai.AiPrompts

/**
 * AI 処理([BookmarkAiProcessingWorker])が長くかかるときの、フォアグラウンド実行の通知。
 * 低い重要度のチャンネルで、音や振動は出さない。通知の権限が無くても(通知が表示されないだけで)フォアグラウンドでの実行は続く。
 */
internal object AiProcessingNotification {
    const val CHANNEL_ID = "ai_processing"
    const val NOTIFICATION_ID = 3002

    /** 通知に出すタイトルの長さの上限(長いタイトルは通知の幅を使い切るため省く) */
    private const val MAX_TITLE_CHARS = 40

    /** 通知のチャンネルを作る(作成済みなら何もしない)。まとめての処理の通知([BulkProcessingNotifier])も使う */
    fun ensureChannel(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.notification_channel_ai_processing))
                .setDescription(context.getString(R.string.notification_channel_ai_processing_desc))
                .build()
        )
    }

    /** [bookmarkTitle] のブックマークの要約を作っている、という通知つきの [ForegroundInfo]。タイトルが分からなければ汎用の文言にする。 */
    fun foregroundInfo(context: Context, bookmarkTitle: String?): ForegroundInfo {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.ai_processing_notification_title))
            .setContentText(progressText(context, bookmarkTitle))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            // ブックマークのタイトルを、ロック画面に出さない
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        // 端末内での推論やファイルの解析が主で、データの処理(dataSync)が最も近い種類。
        // AndroidManifest の SystemForegroundService に同じ種類を宣言している
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun progressText(context: Context, bookmarkTitle: String?): String {
        val title = bookmarkTitle?.trim()?.takeIf { it.isNotEmpty() }
            ?: return context.getString(R.string.ai_processing_notification_summarizing_untitled)
        val shortTitle = if (title.length > MAX_TITLE_CHARS) title.take(MAX_TITLE_CHARS - 1) + "…" else title
        return context.getString(R.string.ai_processing_notification_summarizing, shortTitle)
    }
}

/**
 * 要約の作成をフォアグラウンドで実行するか。WorkManager のワーカーは約 10 分で止められ、バックグラウンドでは
 * プロセスごと止められることもあるため、長くかかる処理は通知つきのフォアグラウンドにする。
 * - 端末内 AI: モデルの読み込みと推論で、短い本文でも数十秒〜数分かかる
 * - 長い本文: 部分ごとに複数回の推論・呼び出しをする
 * - ファイルそのもの(PDF・音声・動画など)を渡す: 送信と解析に時間がかかる
 * 短い本文のクラウド API は数秒で終わるため、通知を出さない。
 */
internal fun shouldRunInForeground(usesLocalLlm: Boolean, textChars: Int, hasAttachment: Boolean): Boolean =
    usesLocalLlm || hasAttachment || textChars > AiPrompts.LONG_TEXT_CHARS
