package com.unchunks.echomark.data.ai.model

import com.unchunks.components.testing.TestDispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelCatalogDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class FakeClient(private val dir: File) : ModelDownloadClient {
        val enqueued = mutableListOf<String>()
        val removed = mutableListOf<Long>()
        var progress: ModelDownloadProgress? = null

        override fun enqueue(url: String, title: String, fileName: String): Long {
            enqueued += url
            return 7
        }

        override fun query(id: Long): ModelDownloadProgress? = progress

        override fun remove(id: Long) {
            removed += id
        }

        override fun fileOf(fileName: String) = File(dir, fileName)
    }

    private val entry = ModelCatalog.entries.first { !it.requiresAgreement }
    private val client by lazy { FakeClient(temp.root) }
    private val activeFile by lazy { File(temp.root, "models/active.json") }
    private val installs = mutableListOf<ModelCatalogEntry>()
    private var installResult = true
    private var usable = Long.MAX_VALUE

    private fun downloader() = ModelCatalogDownloader(
        client,
        { _, entry ->
            installs += entry
            installResult
        },
        activeFile,
        { usable },
        TestDispatcherProvider(Dispatchers.Unconfined)
    )

    private fun progress(status: ModelDownloadProgress.Status, downloaded: Long = 0) =
        ModelDownloadProgress(status, downloaded, entry.sizeBytes)

    @Test
    fun 固定したURLのダウンロードを始める() = runBlocking {
        val downloader = downloader()

        downloader.start(entry)

        assertEquals(listOf(entry.downloadUrl), client.enqueued)
        assertEquals(CatalogDownloadState.Downloading(entry, 0, waitingForNetwork = false), downloader.state.value)
    }

    @Test
    fun 進み具合とWi_Fi待ちを伝える() = runBlocking {
        val downloader = downloader()
        downloader.start(entry)

        client.progress = progress(ModelDownloadProgress.Status.RUNNING, downloaded = 1234)
        downloader.refresh()
        assertEquals(CatalogDownloadState.Downloading(entry, 1234, waitingForNetwork = false), downloader.state.value)

        client.progress = progress(ModelDownloadProgress.Status.WAITING_FOR_NETWORK, downloaded = 1234)
        downloader.refresh()
        assertEquals(CatalogDownloadState.Downloading(entry, 1234, waitingForNetwork = true), downloader.state.value)
    }

    @Test
    fun 終わったら取り込み_一時ファイルと記録を消す() = runBlocking {
        val downloader = downloader()
        downloader.start(entry)

        client.progress = progress(ModelDownloadProgress.Status.SUCCESSFUL)
        downloader.refresh()

        assertEquals(listOf(entry), installs)
        assertEquals(listOf(7L), client.removed)
        assertEquals(CatalogDownloadState.Idle, downloader.state.value)
        assertFalse(activeFile.exists())
    }

    @Test
    fun アプリを起動し直しても続きから進み具合を読める() = runBlocking {
        downloader().start(entry)

        val restarted = downloader()
        assertEquals(CatalogDownloadState.Idle, restarted.state.value)
        client.progress = progress(ModelDownloadProgress.Status.SUCCESSFUL)
        restarted.refresh()

        assertEquals(listOf(entry), installs)
    }

    @Test
    fun 値が合わないファイルは取り込まず_失敗を伝えてやり直せる() = runBlocking {
        installResult = false
        val downloader = downloader()
        downloader.start(entry)

        client.progress = progress(ModelDownloadProgress.Status.SUCCESSFUL)
        downloader.refresh()

        assertTrue(downloader.state.value is CatalogDownloadState.Failed)
        assertEquals(listOf(7L), client.removed)

        downloader.start(entry)
        assertEquals(2, client.enqueued.size)
    }

    @Test
    fun ダウンロードの失敗を伝える() = runBlocking {
        val downloader = downloader()
        downloader.start(entry)

        client.progress = progress(ModelDownloadProgress.Status.FAILED)
        downloader.refresh()

        assertTrue(downloader.state.value is CatalogDownloadState.Failed)
        assertTrue(installs.isEmpty())
    }

    @Test
    fun 取り消されたら何も無かった状態に戻る() = runBlocking {
        val downloader = downloader()
        downloader.start(entry)

        client.progress = null
        downloader.refresh()

        assertEquals(CatalogDownloadState.Idle, downloader.state.value)
        assertFalse(activeFile.exists())
    }

    @Test
    fun 中止するとファイルを消す() = runBlocking {
        val downloader = downloader()
        downloader.start(entry)

        downloader.cancel()

        assertEquals(listOf(7L), client.removed)
        assertEquals(CatalogDownloadState.Idle, downloader.state.value)
    }

    @Test
    fun ダウンロード中はほかのモデルを始めない() = runBlocking {
        val other = ModelCatalog.entries.last()
        val downloader = downloader()
        downloader.start(entry)

        downloader.start(other)

        assertEquals(listOf(entry.downloadUrl), client.enqueued)
    }

    @Test
    fun 取り込むまでの2倍の空きが無ければ始めない() = runBlocking {
        usable = entry.sizeBytes * 2
        val downloader = downloader()

        downloader.start(entry)

        assertTrue(client.enqueued.isEmpty())
        assertTrue(downloader.state.value is CatalogDownloadState.Failed)
        assertTrue((downloader.state.value as CatalogDownloadState.Failed).message.contains("空き容量"))
    }

    @Test
    fun 前に残ったファイルは始める前に消す() = runBlocking {
        val stale = File(temp.root, entry.fileName).apply { writeText("old") }

        downloader().start(entry)

        assertFalse(stale.exists())
    }
}
