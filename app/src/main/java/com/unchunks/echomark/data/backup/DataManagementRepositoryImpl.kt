package com.unchunks.echomark.data.backup

import android.content.Context
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.unchunks.echomark.data.ai.model.ModelManager
import com.unchunks.echomark.data.attachment.AttachmentStore
import com.unchunks.echomark.data.local.dao.BackupDao
import com.unchunks.echomark.data.local.objectbox.VectorSearchDataSource
import com.unchunks.echomark.di.DatabaseModule
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.repository.ApiKeyRepository
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.BackupExportSummary
import com.unchunks.echomark.domain.repository.BackupImportSummary
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.DataManagementRepository
import com.unchunks.echomark.domain.repository.DataOperationException
import com.unchunks.echomark.domain.repository.StorageUsage
import com.unchunks.echomark.worker.BookmarkWorkScheduler
import com.unchunks.echomark.worker.RediscoverDigestScheduler
import com.unchunks.echomark.worker.ReembedAllWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import io.objectbox.BoxStore
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataManagementRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupDao: BackupDao,
    private val vectorSearch: VectorSearchDataSource,
    private val boxStore: BoxStore,
    private val modelManager: ModelManager,
    private val workManager: WorkManager,
    private val bookmarkRepository: BookmarkRepository,
    private val appSettings: AppSettingsRepository,
    private val apiKeyRepository: ApiKeyRepository,
    private val workScheduler: BookmarkWorkScheduler,
    private val attachmentStore: AttachmentStore,
    private val dispatcherProvider: DispatcherProvider
) : DataManagementRepository {

    override suspend fun exportBackup(uri: String): BackupExportSummary = withContext(dispatcherProvider.io) {
        val data = backupDao.snapshot(exportedAt = System.currentTimeMillis())
        try {
            // "wt": 既存ファイルを選んだ場合も中身を切り詰めて上書きする
            val output = context.contentResolver.openOutputStream(uri.toUri(), "wt")
                ?: throw IOException("openOutputStream returned null")
            output.bufferedWriter(Charsets.UTF_8).use { BackupJson.write(data, it) }
        } catch (e: IOException) {
            Timber.w(e, "バックアップの書き出しに失敗")
            throw DataOperationException("ファイルに書き込めませんでした。保存先を変えてもう一度お試しください", e)
        } catch (e: SecurityException) {
            Timber.w(e, "バックアップの書き出し先にアクセスできない")
            throw DataOperationException("保存先にアクセスできませんでした", e)
        }
        Timber.i("バックアップを書き出し: bookmarks=${data.bookmarks.size}")
        BackupExportSummary(
            bookmarks = data.bookmarks.size,
            tags = data.tags.size,
            conversations = data.conversations.size,
            messages = data.messages.size
        )
    }

    override suspend fun importBackup(uri: String): BackupImportSummary = withContext(dispatcherProvider.io) {
        val text = try {
            val input = context.contentResolver.openInputStream(uri.toUri())
                ?: throw IOException("openInputStream returned null")
            input.use { readBackupText(it, MAX_BACKUP_BYTES) }
        } catch (e: BackupFormatException) {
            throw DataOperationException(e.message ?: "バックアップを読み込めませんでした", e)
        } catch (e: IOException) {
            Timber.w(e, "バックアップの読み込みに失敗")
            throw DataOperationException("ファイルを読み込めませんでした", e)
        } catch (e: SecurityException) {
            Timber.w(e, "バックアップファイルにアクセスできない")
            throw DataOperationException("ファイルにアクセスできませんでした", e)
        }
        val decoded = try {
            BackupJson.decode(text)
        } catch (e: BackupFormatException) {
            throw DataOperationException(e.message ?: "バックアップを読み込めませんでした", e)
        }

        // 添付ファイルの本体はバックアップに含めないため、この端末に無いファイルは「ファイルなし」として読み込む
        val data = decoded.data.copy(
            bookmarks = decoded.data.bookmarks.map { bookmark ->
                if (bookmark.filePath != null && attachmentStore.existingFile(bookmark.filePath) == null) {
                    bookmark.copy(filePath = null)
                } else {
                    bookmark
                }
            }
        )
        val merged = backupDao.merge(data)
        Timber.i("バックアップを読み込み: added=${merged.bookmarksAdded}, skipped=${merged.bookmarksSkipped}")

        if (merged.bookmarksAdded > 0) {
            // 埋め込みは書き出していないので作り直す(未作成・旧版のものだけを処理するワーカー)。
            // 実行中の分に続けて必ずもう一度走らせる
            workManager.enqueueUniqueWork(
                ReembedAllWorker.WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<ReembedAllWorker>()
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                    .build()
            )
            // 書き出し時に処理待ちだったもの(準備待ちに変換済み)の要約・タグ付けを再開する。本文が未取得の URL は本文の取得から
            bookmarkRepository.enqueueWaitingModelProcessing()
        }

        BackupImportSummary(
            bookmarksAdded = merged.bookmarksAdded,
            bookmarksSkipped = merged.bookmarksSkipped,
            tagsAdded = merged.tagsAdded,
            conversationsAdded = merged.conversationsAdded,
            conversationsSkipped = merged.conversationsSkipped,
            messagesAdded = merged.messagesAdded,
            invalidRecords = decoded.invalidRecords
        )
    }

    override suspend fun storageUsage(): StorageUsage = withContext(dispatcherProvider.io) {
        val dbFile = context.getDatabasePath(DatabaseModule.DATABASE_NAME)
        // WAL モードのため、本体・-wal・-shm の合計
        val databaseBytes = listOf("", "-wal", "-shm").sumOf { suffix ->
            File(dbFile.path + suffix).takeIf { it.isFile }?.length() ?: 0L
        }
        StorageUsage(
            databaseBytes = databaseBytes,
            embeddingBytes = runCatching { boxStore.dbSizeOnDisk }.getOrDefault(0L),
            modelBytes = modelManager.installedModel.value?.sizeBytes ?: 0L,
            attachmentBytes = attachmentStore.totalBytes()
        )
    }

    override suspend fun deleteAllData(resetSettings: Boolean) = withContext(dispatcherProvider.io) {
        // 処理待ち・実行中の本文取得・AI 処理・埋め込みの作り直しを止める(削除後にクラウドへ送ったり、書き込んだりしない)
        workScheduler.cancelAll()
        workManager.cancelUniqueWork(ReembedAllWorker.WORK_NAME)
        backupDao.deleteAll()
        vectorSearch.deleteAll()
        // 添付ファイルは参照が無くなったので、掃除を待たずにすぐ消す
        attachmentStore.deleteAll()
        if (resetSettings) {
            appSettings.resetToDefaults()
            ApiProvider.entries.forEach { apiKeyRepository.clearKey(it) }
            // 初期化で再発見通知はオフになるので、登録済みの予定も取り消す
            RediscoverDigestScheduler.cancel(workManager)
        }
        Timber.i("全データを削除 (resetSettings=$resetSettings)")
    }
}

/**
 * 読み込むバックアップの大きさの上限。全体を文字列にしてから JSON として解析するため、
 * メモリに収まる大きさに抑える(ブックマーク数千件分の本文を含めても足りる大きさ)。
 */
internal const val MAX_BACKUP_BYTES: Long = 32L * 1024 * 1024

/** [input] を UTF-8 の文字列として読む。[maxBytes] を超えたら読むのをやめて [BackupFormatException]。 */
internal fun readBackupText(input: InputStream, maxBytes: Long): String {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) {
            throw BackupFormatException("ファイルが大きすぎます(上限 ${maxBytes / (1024 * 1024)}MB)。EchoMark のバックアップファイルか確認してください")
        }
        out.write(buffer, 0, read)
    }
    return out.toString(Charsets.UTF_8.name())
}
