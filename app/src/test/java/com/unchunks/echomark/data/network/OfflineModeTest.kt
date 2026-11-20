package com.unchunks.echomark.data.network

import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.ai.model.CatalogDownloadState
import com.unchunks.echomark.data.ai.model.ModelCatalog
import com.unchunks.echomark.data.ai.model.ModelCatalogDownloader
import com.unchunks.echomark.data.ai.model.ModelDownloadClient
import com.unchunks.echomark.data.ai.model.ModelDownloadProgress
import com.unchunks.echomark.di.NetworkModule
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.FakeNetworkGate
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** オフラインモードの守り(共通の OkHttpClient・設定の読み方・切り替え)。 */
class OfflineModeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun get(client: okhttp3.OkHttpClient) =
        client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { it.code }

    @Test
    fun 共通のclientとそこから作ったclientはオフラインモードの間つながない() {
        val gate = FakeNetworkGate(offline = true)
        val shared = NetworkModule.baseOkHttpClient(gate)
        // 各所は共通の client から newBuilder() で作る(インターセプターを引き継ぐ)
        val derived = shared.newBuilder().followRedirects(false).build()

        for (client in listOf(shared, derived)) {
            try {
                get(client)
                fail("オフラインモードなのに通信できた")
            } catch (e: OfflineModeException) {
                assertTrue(e.isOfflineModeBlock())
            }
        }
        assertEquals(0, server.requestCount)

        // オフにしたら、同じ client で通信できる
        gate.offline = false
        server.enqueue(MockResponse.Builder().code(204).build())
        assertEquals(204, get(derived))
    }

    @Test
    fun 断った失敗は包まれていても見分けられる() {
        val wrapped = IllegalStateException("取得に失敗", IOException("包み", OfflineModeException("example.com")))
        assertTrue(wrapped.isOfflineModeBlock())
        assertFalse(IOException("ふつうの通信の失敗").isOfflineModeBlock())
    }

    @Test
    fun 設定を読む前でもその場で読んで答え_切り替えはすぐ効く() {
        val settings = FakeAppSettingsRepository()
        settings.offlineModeFlow.value = true
        val gate = SettingsNetworkGate(settings, TestDispatcherProvider(Dispatchers.Unconfined))

        assertTrue(gate.isOffline())

        // 設定が変わったら追いかける
        settings.offlineModeFlow.value = false
        assertFalse(gate.isOffline())

        // 書き込みを待たずに効かせる
        gate.remember(true)
        assertTrue(gate.isOffline())
    }

    private class FakeDownloadClient(private val dir: File) : ModelDownloadClient {
        val removed = mutableListOf<Long>()
        override fun enqueue(url: String, title: String, fileName: String): Long = 7
        override fun query(id: Long): ModelDownloadProgress? =
            ModelDownloadProgress(ModelDownloadProgress.Status.RUNNING, 0, 1)
        override fun remove(id: Long) {
            removed += id
        }
        override fun fileOf(fileName: String) = File(dir, fileName)
    }

    @Test
    fun オンにすると通信を止めてモデルのダウンロードもやめ_オフにすると待っていた処理を積み直す() = runBlocking {
        val settings = FakeAppSettingsRepository()
        val dispatchers = TestDispatcherProvider(Dispatchers.Unconfined)
        val gate = SettingsNetworkGate(settings, dispatchers)
        val downloadClient = FakeDownloadClient(temp.root)
        val downloader = ModelCatalogDownloader(
            downloadClient,
            { _, _ -> true },
            File(temp.root, "models/active.json"),
            { Long.MAX_VALUE },
            dispatchers,
            gate::isOffline
        )
        val entry = ModelCatalog.entries.first { !it.requiresAgreement }
        downloader.start(entry)
        val bookmarks = FakeBookmarkRepository()
        val controller = DefaultOfflineModeController(
            settings, gate, NetworkModule.baseOkHttpClient(gate), Lazy { downloader }, Lazy { bookmarks }
        )

        controller.setOfflineMode(true)

        assertTrue(settings.offlineModeFlow.value)
        assertTrue(gate.isOffline())
        assertEquals(listOf(7L), downloadClient.removed)
        assertTrue(downloader.state.value is CatalogDownloadState.Failed)
        assertTrue(bookmarks.waitingProcessingCalls.isEmpty())

        // オフラインモードの間はダウンロードを始めない
        downloader.clearFailure()
        downloader.start(entry)
        assertTrue(downloader.state.value is CatalogDownloadState.Failed)

        controller.setOfflineMode(false)

        assertFalse(settings.offlineModeFlow.value)
        assertFalse(gate.isOffline())
        // 夜間にまとめて処理する設定なら、積み直す側(enqueueSequential)が夜を待つ
        assertEquals(listOf(false), bookmarks.waitingProcessingCalls)
    }
}
