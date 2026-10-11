package com.unchunks.echomark.testing

import com.unchunks.echomark.domain.repository.BackupExportSummary
import com.unchunks.echomark.domain.repository.BackupImportSummary
import com.unchunks.echomark.domain.repository.DataManagementRepository
import com.unchunks.echomark.domain.repository.RediscoverSettings
import com.unchunks.echomark.domain.repository.StorageUsage
import com.unchunks.echomark.worker.RediscoverScheduleController

/** データ操作の Fake。呼び出しを記録し、[failure] があれば投げる。 */
class FakeDataManagementRepository : DataManagementRepository {
    var exportSummary = BackupExportSummary(bookmarks = 3, tags = 2, conversations = 1, messages = 4)
    var importSummary = BackupImportSummary(
        bookmarksAdded = 2, bookmarksSkipped = 1, tagsAdded = 1,
        conversationsAdded = 1, conversationsSkipped = 0, messagesAdded = 2, invalidRecords = 0
    )
    var storage = StorageUsage(databaseBytes = 1024, embeddingBytes = 2048, modelBytes = 0)
    var failure: Throwable? = null

    val exportedUris = mutableListOf<String>()
    val importedUris = mutableListOf<String>()
    val deleteCalls = mutableListOf<Boolean>()
    var storageCalls = 0

    override suspend fun exportBackup(uri: String): BackupExportSummary {
        exportedUris += uri
        failure?.let { throw it }
        return exportSummary
    }

    override suspend fun importBackup(uri: String): BackupImportSummary {
        importedUris += uri
        failure?.let { throw it }
        return importSummary
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
