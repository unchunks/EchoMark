package com.unchunks.echomark.data.backup

import com.unchunks.echomark.domain.backup.AutoBackupSettings
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.DataManagementRepository
import com.unchunks.echomark.domain.repository.DataOperationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** 自動バックアップを書き出せなかったことを、利用者に知らせる(通知)。テストでは Fake に差し替える */
interface AutoBackupNotifier {
    /** 保存先のフォルダを使う許可がなくなった(フォルダを消された・許可を取り消された) */
    fun notifyFolderUnavailable()
}

/**
 * 自動バックアップを1回行う([com.unchunks.echomark.worker.AutoBackupWorker] が毎晩呼ぶ。「今すぐバックアップ」からも)。
 *
 * - 書き出しは手動の書き出しと同じ([DataManagementRepository.exportBackup])。名前は [AutoBackupSettings.FILE_PREFIX] + 日時
 * - 書き出せたら、自動バックアップで作ったファイル(名前で見分ける)のうち、新しい [AutoBackupSettings.keep] 個を残して消す。
 *   利用者が自分で置いたファイルには触れない
 * - 保存先を使う許可がなくなっていたら、黙って失敗せず通知で知らせ、設定画面にも理由を出す
 * - ブックマークが1件も無いときは書き出さない(全部消したあとの空のバックアップで、前の世代を押し出さない)
 */
class AutoBackupRunner @Inject constructor(
    private val appSettings: AppSettingsRepository,
    private val dataRepository: DataManagementRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val folder: BackupFolder,
    private val notifier: AutoBackupNotifier
) {

    enum class Outcome { SKIPPED, SUCCEEDED, FOLDER_UNAVAILABLE, FAILED }

    suspend fun run(now: Long = System.currentTimeMillis()): Outcome {
        val settings = appSettings.autoBackup.first()
        val treeUri = settings.folderUri
        if (!settings.enabled || treeUri == null) return Outcome.SKIPPED
        if (!folder.canWrite(treeUri)) return folderUnavailable()
        if (bookmarkRepository.getAllBookmarkIds().isEmpty()) {
            Timber.i("自動バックアップ: ブックマークが無いので書き出さない")
            return Outcome.SKIPPED
        }

        val extension = if (settings.includeFiles) "zip" else "json"
        val mimeType = if (settings.includeFiles) "application/zip" else "application/json"
        val created = try {
            folder.create(treeUri, fileName(now, extension), mimeType)
        } catch (e: SecurityException) {
            return folderUnavailable()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "自動バックアップのファイルを作れない")
            return failed("保存先にファイルを作れませんでした。空き容量とフォルダを確かめてください")
        }
        try {
            dataRepository.exportBackup(created, settings.includeFiles)
        } catch (e: CancellationException) {
            runCatching { folder.delete(created) }
            throw e
        } catch (e: Exception) {
            // 途中までのファイルを残さない(壊れた世代を数えないように)
            runCatching { folder.delete(created) }
            if (e is SecurityException || e.cause is SecurityException) return folderUnavailable()
            Timber.w(e, "自動バックアップを書き出せない")
            return failed((e as? DataOperationException)?.message ?: "書き出せませんでした")
        }

        prune(treeUri, settings.keep)
        appSettings.updateAutoBackup { it.copy(lastSuccessAt = now, lastError = null) }
        Timber.i("自動バックアップを書き出した")
        return Outcome.SUCCEEDED
    }

    /** 自動バックアップで作ったファイルのうち、新しい [keep] 個を残して消す(名前に日時が入っているので、名前の順が新しさの順) */
    private fun prune(treeUri: String, keep: Int) {
        try {
            folder.list(treeUri)
                .filter { isAutoBackupFile(it.name) }
                .sortedByDescending { it.name }
                .drop(keep)
                .forEach { if (!folder.delete(it.uri)) Timber.w("古い自動バックアップを消せない: %s", it.name) }
        } catch (e: Exception) {
            // 消せなくても、書き出しはできている
            Timber.w(e, "古い自動バックアップを整理できない")
        }
    }

    private suspend fun folderUnavailable(): Outcome {
        appSettings.updateAutoBackup { it.copy(lastError = FOLDER_UNAVAILABLE_MESSAGE) }
        notifier.notifyFolderUnavailable()
        return Outcome.FOLDER_UNAVAILABLE
    }

    private suspend fun failed(message: String): Outcome {
        appSettings.updateAutoBackup { it.copy(lastError = message) }
        return Outcome.FAILED
    }

    companion object {
        const val FOLDER_UNAVAILABLE_MESSAGE = "保存先のフォルダを使えなくなりました。設定の「自動バックアップ」で、保存先を選び直してください"

        private val NAME_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        internal fun fileName(now: Long, extension: String, zone: ZoneId = ZoneId.systemDefault()): String =
            AutoBackupSettings.FILE_PREFIX + NAME_TIME.format(Instant.ofEpochMilli(now).atZone(zone)) + "." + extension

        internal fun isAutoBackupFile(name: String): Boolean =
            name.startsWith(AutoBackupSettings.FILE_PREFIX) && (name.endsWith(".json") || name.endsWith(".zip"))
    }
}
