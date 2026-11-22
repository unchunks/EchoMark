package com.unchunks.echomark.data.video

import com.unchunks.echomark.data.remote.PeerTubeFetcher
import com.unchunks.echomark.domain.video.VideoDownloadFailure
import com.unchunks.echomark.domain.video.VideoFileSourceKind
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 動画のファイルのダウンロード(ローカルのサーバーで、本物の通信はしない)。続きから落とす・止めるところは、
 * 部品の web-content の ResumableDownloaderTest で確かめる。
 */
class VideoFileDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var downloader: VideoFileDownloader
    private lateinit var part: File
    private val progress = mutableListOf<Pair<Long, Long?>>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // テストのサーバーはローカル(127.0.0.1)にあるため、アドレスの制限を外す
        downloader = VideoFileDownloader(OkHttpClient()) { true }
        part = File(temp.root, "1.part")
    }

    @After
    fun tearDown() {
        server.close()
    }

    private val body = ByteArray(1500) { (it % 251).toByte() }

    private fun download(
        maxBytes: Long = 10_000,
        free: Long = Long.MAX_VALUE,
        path: String = "/a.mp4"
    ) = downloader.download(
        source = VideoFileDownloader.Source(server.url(path).toString(), label = null),
        part = part,
        maxBytes = maxBytes,
        freeBytes = { free },
        isStopped = { false },
        onProgress = { done, total -> progress += done to total }
    )

    private fun video(bytes: ByteArray, type: String = "video/mp4") =
        MockResponse.Builder().addHeader("Content-Type", type).body(Buffer().write(bytes)).build()

    @Test
    fun 動画のファイルを落とす() {
        server.enqueue(video(body))

        val result = download()

        assertEquals("video/mp4", result.mimeType)
        assertEquals(1500L, result.size)
        assertTrue(body.contentEquals(part.readBytes()))
        assertEquals(1500L to 1500L, progress.last())
    }

    @Test
    fun 汎用の種類でも拡張子が動画なら落とす() {
        server.enqueue(video(body, type = "application/octet-stream"))

        assertEquals("video/mp4", download(path = "/clip.mp4").mimeType)
    }

    @Test
    fun 動画でなければ落とさない() {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/html").body("<html></html>").build())

        assertFailure(VideoDownloadFailure.NOT_VIDEO) { download(path = "/page") }
        assertFalse(part.exists() && part.length() > 0)
    }

    @Test
    fun 上限を超えるファイルは落とさない() {
        server.enqueue(video(body))

        assertFailure(VideoDownloadFailure.OVER_LIMIT) { download(maxBytes = 1000) }
    }

    @Test
    fun 空き容量が足りなければ落とさない() {
        server.enqueue(video(body))

        assertFailure(VideoDownloadFailure.NO_SPACE) { download(free = VideoFileDownloader.STORAGE_MARGIN_BYTES) }
    }

    @Test
    fun PeerTubeは投稿者がダウンロードを許していなければ落とさない() {
        peerTube(downloadEnabled = false)

        assertFailure(VideoDownloadFailure.NOT_ALLOWED) {
            downloader.resolve(server.url("/w/$VIDEO_ID").toString(), VideoFileSourceKind.PEERTUBE)
        }
    }

    @Test
    fun PeerTubeは許された720p以下のいちばん高い画質のファイルを選ぶ() {
        peerTube(downloadEnabled = true)

        val source = downloader.resolve(server.url("/w/$VIDEO_ID").toString(), VideoFileSourceKind.PEERTUBE)

        assertEquals(server.url("/download/videos/720.mp4").toString(), source.url)
        assertEquals("720p", source.label)
    }

    @Test
    fun ファイルの選び方() {
        val files = listOf(
            PeerTubeFetcher.File("a", 1080, "1080p", null),
            PeerTubeFetcher.File("b", 360, "360p", null)
        )
        assertEquals("b", VideoFileDownloader.chooseFile(files)?.url)
        assertEquals("a", VideoFileDownloader.chooseFile(files.take(1))?.url)
        assertNull(VideoFileDownloader.chooseFile(emptyList()))
    }

    private fun peerTube(downloadEnabled: Boolean) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/v1/videos/$VIDEO_ID" -> MockResponse.Builder()
                    .addHeader("Content-Type", "application/json")
                    .body(
                        """
                        {"uuid":"u","name":"動画","isLive":false,"downloadEnabled":$downloadEnabled,
                         "files":[
                           {"fileDownloadUrl":"/download/videos/1080.mp4","resolution":{"id":1080,"label":"1080p"},"size":900},
                           {"fileDownloadUrl":"/download/videos/720.mp4","resolution":{"id":720,"label":"720p"},"size":500},
                           {"fileDownloadUrl":"/download/videos/audio.m4a","resolution":{"id":0,"label":"Audio"},"hasVideo":false}
                         ],
                         "streamingPlaylists":[{"files":[{"fileDownloadUrl":"/download/hls/480.mp4","resolution":{"id":480,"label":"480p"}}]}]}
                        """.trimIndent()
                    )
                    .build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
    }

    private fun assertFailure(reason: VideoDownloadFailure, block: () -> Unit) {
        try {
            block()
            fail("失敗しなかった")
        } catch (e: VideoDownloadException) {
            assertEquals(reason, e.reason)
        }
    }

    private companion object {
        const val VIDEO_ID = "9c9de5e8xQNXhHR4Fvu8Qa"
    }
}
