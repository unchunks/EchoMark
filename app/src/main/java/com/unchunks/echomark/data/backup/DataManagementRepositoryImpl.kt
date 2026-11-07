package com.unchunks.echomark.data.backup

import android.content.Context
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.components.llm.ApiProvider
import com.unchunks.echomark.data.ai.model.ModelManager
import com.unchunks.echomark.data.attachment.AttachmentStore
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.domain.bookmark.model.AttachmentError
import com.unchunks.echomark.domain.bookmark.model.AttachmentException
import com.unchunks.echomark.domain.bookmark.model.MAX_ATTACHMENT_BYTES
import com.unchunks.echomark.domain.model.ChatTemplate
import com.unchunks.echomark.data.local.dao.BackupDao
import com.unchunks.echomark.data.local.objectbox.VectorSearchDataSource
import com.unchunks.echomark.di.DatabaseModule
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
import com.unchunks.echomark.worker.FeedCheckWorker
import com.unchunks.echomark.worker.ReembedAllWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import io.objectbox.BoxStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
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

    override suspend fun exportBackup(uri: String, includeFiles: Boolean): BackupExportSummary = withContext(dispatcherProvider.io) {
        val data = backupDao.snapshot(exportedAt = System.currentTimeMillis())
            .copy(chatTemplates = appSettings.chatTemplates.first())
        // ファイルも含めるときは、ブックマークが指している添付ファイル・オフラインコピーのうち、端末にあるものを入れる
        val files = if (includeFiles) {
            referencedFiles(data.bookmarks).mapNotNull { path -> attachmentStore.existingFile(path)?.let { path to it } }.toMap()
        } else {
            emptyMap()
        }
        try {
            // "wt": 既存ファイルを選んだ場合も中身を切り詰めて上書きする
            val output = context.contentResolver.openOutputStream(uri.toUri(), "wt")
                ?: throw IOException("openOutputStream returned null")
            output.buffered().use { out ->
                if (includeFiles) {
                    BackupArchive.write(data, files, out)
                } else {
                    val writer = out.writer(Charsets.UTF_8)
                    BackupJson.write(data, writer)
                    writer.flush()
                }
            }
        } catch (e: IOException) {
            Timber.w(e, "バックアップの書き出しに失敗")
            throw DataOperationException("ファイルに書き込めませんでした。保存先の空き容量を確かめて、もう一度お試しください", e)
        } catch (e: SecurityException) {
            Timber.w(e, "バックアップの書き出し先にアクセスできない")
            throw DataOperationException("保存先にアクセスできませんでした", e)
        }
        Timber.i("バックアップを書き出し: bookmarks=${data.bookmarks.size}, files=${files.size}")
        BackupExportSummary(
            bookmarks = data.bookmarks.size,
            tags = data.tags.size,
            conversations = data.conversations.size,
            messages = data.messages.size,
            files = files.size
        )
    }

    /**
     * バックアップを今のデータに統合する。次の順で、全体を確かめてから書き込む(途中まで読み込まない):
     * 1. ファイルを読む。zip なら、キャッシュの一時的な場所に展開する(名前・大きさ・空き容量を確かめる)
     * 2. JSON を読み、中身がそろっているかを確かめる([BackupValidation])。問題があれば何も書かずに断る
     * 3. 添付ファイル・オフラインコピーを置き場所に入れる → チャットのテンプレートを足す → DB に統合する(1つのトランザクション)。
     *    どこかで失敗したら、入れたファイルを消し、テンプレートを元に戻す(DB はトランザクションで元のまま)
     */
    override suspend fun importBackup(uri: String): BackupImportSummary = withContext(dispatcherProvider.io) {
        val staging = File(context.cacheDir, IMPORT_STAGING_DIRECTORY)
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            val read = readBackup(uri, staging)
            val decoded = try {
                BackupJson.decode(read.jsonText)
            } catch (e: BackupFormatException) {
                throw DataOperationException(e.message ?: "バックアップを読み込めませんでした", e)
            }
            val problems = BackupValidation.problemsOf(decoded)
            if (problems.isNotEmpty()) {
                Timber.w("バックアップに問題があるため読み込まない: %s", problems)
                throw DataOperationException(BackupValidation.message(problems))
            }
            restore(decoded.data, read.files)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** [uri] のバックアップ(JSON か zip)を読む。zip なら [staging] に展開する */
    private fun readBackup(uri: String, staging: File): BackupArchive.Staged = try {
        val input = context.contentResolver.openInputStream(uri.toUri())
            ?: throw IOException("openInputStream returned null")
        input.buffered().use { buffered ->
            buffered.mark(ZIP_HEADER_BYTES)
            val header = ByteArray(ZIP_HEADER_BYTES)
            val headerLength = buffered.read(header).coerceAtLeast(0)
            buffered.reset()
            if (BackupArchive.isZip(header.copyOf(headerLength))) {
                BackupArchive.extract(
                    buffered,
                    staging,
                    maxJsonBytes = MAX_BACKUP_BYTES,
                    maxFileBytes = MAX_ATTACHMENT_BYTES,
                    maxTotalBytes = attachmentStore.restorableBytes()
                )
            } else {
                BackupArchive.Staged(readBackupText(buffered, MAX_BACKUP_BYTES), emptyMap())
            }
        }
    } catch (e: BackupFormatException) {
        throw DataOperationException(e.message ?: "バックアップを読み込めませんでした", e)
    } catch (e: IOException) {
        Timber.w(e, "バックアップの読み込みに失敗")
        throw DataOperationException("ファイルを読み込めませんでした", e)
    } catch (e: SecurityException) {
        Timber.w(e, "バックアップファイルにアクセスできない")
        throw DataOperationException("ファイルにアクセスできませんでした", e)
    }

    /** 確かめたバックアップを書き込む。失敗したら、それまでに書いたもの(ファイル・テンプレート)を元に戻す */
    private suspend fun restore(backup: BackupData, stagedFiles: Map<String, File>): BackupImportSummary {
        val placedFiles = mutableListOf<String>()
        var templatesBefore: List<ChatTemplate>? = null
        val merged = try {
            // zip に入っていて、この端末に無いファイルを入れる
            for (path in referencedFiles(backup.bookmarks)) {
                val staged = stagedFiles[path] ?: continue
                if (attachmentStore.restore(path, staged)) placedFiles += path
            }
            // この端末に無いファイル(JSON だけのバックアップ・zip に無かったもの)は「ファイルなし」として読み込む
            val data = backup.copy(bookmarks = backup.bookmarks.map { it.withExistingFiles() })

            if (data.chatTemplates.isNotEmpty()) {
                templatesBefore = appSettings.chatTemplates.first()
                appSettings.updateChatTemplates { mergeChatTemplates(it, data.chatTemplates) }
            }
            backupDao.merge(data)
        } catch (e: Throwable) {
            // 取り消し(画面を閉じたなど)でも元に戻す
            withContext(NonCancellable) {
                placedFiles.forEach { attachmentStore.delete(it) }
                templatesBefore?.let { before -> runCatching { appSettings.updateChatTemplates { before } } }
            }
            if (e is CancellationException || e is DataOperationException || e is Error) throw e
            Timber.w(e, "バックアップの書き込みに失敗")
            throw DataOperationException(
                if (e is AttachmentException && e.error == AttachmentError.InsufficientStorage) {
                    "端末の空き容量が足りないため、読み込めませんでした(今のデータはそのままです)"
                } else {
                    "読み込めませんでした(今のデータはそのままです)"
                },
                e
            )
        }
        Timber.i("バックアップを読み込み: added=${merged.bookmarksAdded}, skipped=${merged.bookmarksSkipped}, files=${placedFiles.size}")

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

        return BackupImportSummary(
            bookmarksAdded = merged.bookmarksAdded,
            bookmarksSkipped = merged.bookmarksSkipped,
            tagsAdded = merged.tagsAdded,
            conversationsAdded = merged.conversationsAdded,
            conversationsSkipped = merged.conversationsSkipped,
            messagesAdded = merged.messagesAdded,
            filesRestored = placedFiles.size
        )
    }

    /** この端末に無いファイルを指すパスを外す。オフラインコピーの MHTML が無ければ、コピーごと無いものにする */
    private fun BookmarkEntity.withExistingFiles(): BookmarkEntity {
        fun exists(path: String?) = path != null && attachmentStore.existingFile(path) != null
        val hasSnapshot = exists(snapshotPath)
        return copy(
            filePath = filePath.takeIf { exists(it) },
            snapshotPath = snapshotPath.takeIf { hasSnapshot },
            snapshotPdfPath = snapshotPdfPath.takeIf { hasSnapshot && exists(it) },
            snapshotAt = snapshotAt.takeIf { hasSnapshot }
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
            modelBytes = modelManager.totalBytes(),
            attachmentBytes = attachmentStore.totalBytes()
        )
    }

    override suspend fun deleteAllData(resetSettings: Boolean) = withContext(dispatcherProvider.io) {
        // 処理待ち・実行中の本文取得・AI 処理・埋め込みの作り直しを止める(削除後にクラウドへ送ったり、書き込んだりしない)
        workScheduler.cancelAll()
        workManager.cancelUniqueWork(ReembedAllWorker.WORK_NAME)
        workManager.cancelUniqueWork(FeedCheckWorker.WORK_NAME)
        backupDao.deleteAll()
        vectorSearch.deleteAll()
        // 添付ファイルは参照が無くなったので、掃除を待たずにすぐ消す
        attachmentStore.deleteAll()
        // 検索の履歴(何を探したか)と、自分で作ったチャットのテンプレートは利用者のデータなので、設定を残す場合でも消す
        appSettings.updateSearchHistory { emptyList() }
        appSettings.updateChatTemplates { emptyList() }
        // 「両方残す」と答えた重複の組は古い ID を覚えているだけなので、設定を残す場合でも消す
        appSettings.updateDismissedDuplicates { emptySet() }
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

/** zip を展開する一時的な場所(キャッシュの中。読み込みが終わったら消す) */
private const val IMPORT_STAGING_DIRECTORY = "backup-import"

/** zip かどうかを見分けるために読む、先頭のバイト数 */
private const val ZIP_HEADER_BYTES = 4

/** ブックマークが指している添付ファイル・オフラインコピーの相対パス */
internal fun referencedFiles(bookmarks: List<BookmarkEntity>): Set<String> =
    bookmarks.flatMapTo(LinkedHashSet()) { listOfNotNull(it.filePath, it.snapshotPath, it.snapshotPdfPath) }

/**
 * 読み込んだチャットのテンプレートを今のテンプレートの後ろに足す。同じ名前・同じ本文のものは足さない
 * (同じバックアップを2回読み込んでも増えない)。ID が今のものとぶつかったら、新しい ID にする。
 */
internal fun mergeChatTemplates(
    current: List<ChatTemplate>,
    imported: List<ChatTemplate>,
    newId: () -> String = { UUID.randomUUID().toString() }
): List<ChatTemplate> {
    val ids = current.mapTo(HashSet()) { it.id }
    val contents = current.mapTo(HashSet()) { it.title to it.text }
    val added = imported.filter { contents.add(it.title to it.text) }.map { template ->
        (if (ids.add(template.id)) template else template.copy(id = newId().also { ids += it }))
    }
    return current + added
}
