package com.unchunks.echomark.data.backup

import com.unchunks.echomark.domain.backup.AutoBackupSettings
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.repository.DataOperationException
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.FakeDataManagementRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** 自動バックアップ(書き出し・古い世代の整理・保存先を使えなくなったときの知らせ)。 */
class AutoBackupRunnerTest {

    private class FakeFolder : BackupFolder {
        var writable = true
        var failCreate: Exception? = null
        val files = mutableListOf<BackupFolderEntry>()
        val deleted = mutableListOf<String>()

        override fun canWrite(treeUri: String) = writable
        override fun create(treeUri: String, displayName: String, mimeType: String): String {
            failCreate?.let { throw it }
            return "content://doc/$displayName".also { files += BackupFolderEntry(displayName, it) }
        }
        override fun list(treeUri: String) = files.toList()
        override fun delete(documentUri: String): Boolean {
            deleted += documentUri
            return files.removeAll { it.uri == documentUri }
        }
    }

    private class FakeNotifier : AutoBackupNotifier {
        var notified = 0
        override fun notifyFolderUnavailable() {
            notified++
        }
    }

    private val settings = FakeAppSettingsRepository().apply {
        autoBackupFlow.value = AutoBackupSettings(enabled = true, folderUri = "content://tree/backup", folderLabel = "Backup", keep = 2)
    }
    private val data = FakeDataManagementRepository()
    private val bookmarks = FakeBookmarkRepository().apply {
        bookmarks.value = listOf(Bookmark(id = 1, type = BookmarkType.TEXT, title = "メモ", createdAt = 1, lastAccessedAt = 1))
    }
    private val folder = FakeFolder()
    private val notifier = FakeNotifier()
    private val runner = AutoBackupRunner(settings, data, bookmarks, folder, notifier)

    @Test
    fun 書き出して_自動バックアップで作った古いものだけを残す数まで消す() = runBlocking {
        // 利用者が自分で置いたファイルには触れない
        folder.files += BackupFolderEntry("echomark-backup-2026-01-01.json", "content://doc/manual")
        folder.files += BackupFolderEntry("echomark-auto-backup-20260101-020000.json", "content://doc/old1")
        folder.files += BackupFolderEntry("echomark-auto-backup-20260102-020000.json", "content://doc/old2")

        val outcome = runner.run(now = 1_800_000_000_000L)

        assertEquals(AutoBackupRunner.Outcome.SUCCEEDED, outcome)
        assertEquals(1, data.exportedUris.size)
        assertEquals(listOf(false), data.exportIncludeFiles)
        assertEquals(listOf("content://doc/old1"), folder.deleted)
        assertTrue(folder.files.any { it.uri == "content://doc/manual" })
        assertEquals(1_800_000_000_000L, settings.autoBackupFlow.value.lastSuccessAt)
        assertNull(settings.autoBackupFlow.value.lastError)
    }

    @Test
    fun ファイルも含める設定なら_zipで書き出す() = runBlocking {
        settings.autoBackupFlow.value = settings.autoBackupFlow.value.copy(includeFiles = true)

        runner.run(now = 1_800_000_000_000L)

        assertEquals(listOf(true), data.exportIncludeFiles)
        assertTrue(data.exportedUris.single().endsWith(".zip"))
    }

    @Test
    fun 保存先を使う許可がなくなったら_黙って失敗せず通知して理由を残す() = runBlocking {
        folder.writable = false

        val outcome = runner.run()

        assertEquals(AutoBackupRunner.Outcome.FOLDER_UNAVAILABLE, outcome)
        assertEquals(1, notifier.notified)
        assertEquals(AutoBackupRunner.FOLDER_UNAVAILABLE_MESSAGE, settings.autoBackupFlow.value.lastError)
        assertTrue(data.exportedUris.isEmpty())
    }

    @Test
    fun 書き出しの途中で許可を失っても通知し_作りかけのファイルを残さない() = runBlocking {
        data.failure = DataOperationException("保存先にアクセスできませんでした", SecurityException("revoked"))

        val outcome = runner.run()

        assertEquals(AutoBackupRunner.Outcome.FOLDER_UNAVAILABLE, outcome)
        assertEquals(1, notifier.notified)
        assertTrue(folder.files.isEmpty())
    }

    @Test
    fun 書き出せなければ理由を残し_作りかけのファイルを消す() = runBlocking {
        data.failure = DataOperationException("空き容量が足りません")

        val outcome = runner.run()

        assertEquals(AutoBackupRunner.Outcome.FAILED, outcome)
        assertEquals("空き容量が足りません", settings.autoBackupFlow.value.lastError)
        assertTrue(folder.files.isEmpty())
        assertEquals(0, notifier.notified)
    }

    @Test
    fun オフ_保存先なし_ブックマークが無いときは何もしない() = runBlocking {
        bookmarks.bookmarks.value = emptyList()
        assertEquals(AutoBackupRunner.Outcome.SKIPPED, runner.run())

        settings.autoBackupFlow.value = AutoBackupSettings(enabled = false, folderUri = "content://tree/backup")
        assertEquals(AutoBackupRunner.Outcome.SKIPPED, runner.run())

        assertTrue(folder.files.isEmpty())
        assertFalse(data.exportedUris.isNotEmpty())
    }

    @Test
    fun ファイルの名前は日時の順に並び_自動バックアップのものだけを見分ける() {
        assertEquals("echomark-auto-backup-20270115-080000.json", AutoBackupRunner.fileName(1_800_000_000_000L, "json", ZoneOffset.UTC))
        assertTrue(AutoBackupRunner.isAutoBackupFile("echomark-auto-backup-20270115-080000.zip"))
        assertFalse(AutoBackupRunner.isAutoBackupFile("echomark-backup-2027-01-15.json"))
        assertFalse(AutoBackupRunner.isAutoBackupFile("echomark-auto-backup-notes.txt"))
    }
}
