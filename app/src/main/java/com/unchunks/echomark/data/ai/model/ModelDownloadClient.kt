package com.unchunks.echomark.data.ai.model

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/** ダウンロードの進み具合。 */
data class ModelDownloadProgress(val status: Status, val downloadedBytes: Long, val totalBytes: Long) {
    enum class Status {
        /** ダウンロード中・順番待ち */
        RUNNING,

        /** Wi-Fi・ネットワークの接続を待っている */
        WAITING_FOR_NETWORK,
        SUCCESSFUL,
        FAILED
    }
}

/** モデルファイルのダウンロードを端末に任せる部分。ファイルはアプリの外部ストレージの一時的な場所に保存する。 */
interface ModelDownloadClient {
    /** [url] を [fileName] にダウンロードし始める(Wi-Fi のときだけ)。ダウンロードの id を返す。 */
    fun enqueue(url: String, title: String, fileName: String): Long

    /** [id] の進み具合。無ければ(ユーザーが取り消したなど)null。 */
    fun query(id: Long): ModelDownloadProgress?

    /** [id] のダウンロードをやめて、途中のファイル・保存したファイルを消す。 */
    fun remove(id: Long)

    /** [fileName] の保存先。 */
    fun fileOf(fileName: String): File?
}

/** Android の DownloadManager を使う。アプリを閉じてもダウンロードは続き、通知で進み具合が分かる。 */
class AndroidModelDownloadClient @Inject constructor(
    @ApplicationContext private val context: Context
) : ModelDownloadClient {

    private val manager: DownloadManager get() = requireNotNull(context.getSystemService())

    override fun enqueue(url: String, title: String, fileName: String): Long = manager.enqueue(
        DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setAllowedOverMetered(false)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
    )

    override fun query(id: Long): ModelDownloadProgress? =
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            ModelDownloadProgress(
                status = when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> ModelDownloadProgress.Status.SUCCESSFUL
                    DownloadManager.STATUS_FAILED -> ModelDownloadProgress.Status.FAILED
                    DownloadManager.STATUS_PAUSED -> ModelDownloadProgress.Status.WAITING_FOR_NETWORK
                    else -> ModelDownloadProgress.Status.RUNNING
                },
                downloadedBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                totalBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            )
        }

    override fun remove(id: Long) {
        manager.remove(id)
    }

    override fun fileOf(fileName: String): File? =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, fileName) }
}
