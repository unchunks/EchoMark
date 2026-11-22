package com.unchunks.echomark.testing

import com.unchunks.echomark.domain.repository.BackupExportSummary
import com.unchunks.echomark.domain.repository.BackupImportSummary
import com.unchunks.echomark.domain.repository.BackupSettings
import com.unchunks.echomark.domain.repository.DataManagementRepository
import com.unchunks.echomark.domain.repository.ProcessingSettings
import com.unchunks.echomark.domain.repository.RediscoverSettings
import com.unchunks.echomark.domain.repository.StorageUsage
import com.unchunks.echomark.worker.ProcessingScheduleController
import com.unchunks.echomark.worker.RediscoverScheduleController

/** データ操作の Fake。呼び出しを記録し、[failure] があれば投げる。 */
class FakeDataManagementRepository : DataManagementRepository {
    var exportSummary = BackupExportSummary(bookmarks = 3, tags = 2, conversations = 1, messages = 4)
    var importSummary = BackupImportSummary(
        bookmarksAdded = 2, bookmarksSkipped = 1, tagsAdded = 1,
        conversationsAdded = 1, conversationsSkipped = 0, messagesAdded = 2
    )
    var storage = StorageUsage(databaseBytes = 1024, embeddingBytes = 2048, modelBytes = 0)
    var failure: Throwable? = null

    val exportedUris = mutableListOf<String>()
    /** 書き出しでファイルも含めたか(書き出しごと) */
    val exportIncludeFiles = mutableListOf<Boolean>()
    val importedUris = mutableListOf<String>()
    val deleteCalls = mutableListOf<Boolean>()
    var storageCalls = 0

    override suspend fun exportBackup(uri: String, includeFiles: Boolean): BackupExportSummary {
        exportedUris += uri
        exportIncludeFiles += includeFiles
        failure?.let { throw it }
        return exportSummary
    }

    override suspend fun importBackup(uri: String): BackupImportSummary {
        importedUris += uri
        failure?.let { throw it }
        return importSummary
    }

    /** restoreSettings で受け取った設定 */
    val restoredSettings = mutableListOf<BackupSettings>()

    override suspend fun restoreSettings(settings: BackupSettings): Int {
        restoredSettings += settings
        failure?.let { throw it }
        return settings.count
    }

    override suspend fun storageUsage(): StorageUsage {
        storageCalls++
        return storage
    }

    override suspend fun deleteAllData(resetSettings: Boolean) {
        deleteCalls += resetSettings
        failure?.let { throw it }
    }
}

/** 反映された再発見通知の設定を記録する。 */
class FakeRediscoverScheduleController : RediscoverScheduleController {
    val applied = mutableListOf<RediscoverSettings>()
    override fun apply(settings: RediscoverSettings) {
        applied += settings
    }
}

/** 処理の設定の反映を記録する Fake。 */
class FakeProcessingScheduleController : ProcessingScheduleController {
    val changes = mutableListOf<Pair<ProcessingSettings, ProcessingSettings>>()
    override fun ensureScheduled(settings: ProcessingSettings) = Unit
    override suspend fun onSettingsChanged(previous: ProcessingSettings, current: ProcessingSettings) {
        changes += previous to current
    }
}

/** 「今すぐバックアップ」の呼び出しを数える Fake。 */
class FakeAutoBackupController : com.unchunks.echomark.worker.AutoBackupController {
    var runs = 0
    override fun runNow() {
        runs++
    }
}
