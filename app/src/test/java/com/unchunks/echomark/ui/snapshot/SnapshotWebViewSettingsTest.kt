package com.unchunks.echomark.ui.snapshot

import android.content.Context
import android.webkit.WebSettings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** オフラインコピーを表示する WebView の安全のための設定(JavaScript・通信・ほかのファイルへのアクセスを許さない)。 */
@RunWith(AndroidJUnit4::class)
class SnapshotWebViewSettingsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fileUrl = "file:///data/user/0/com.unchunks.echomark/files/attachments/abc.mhtml"

    @Test
    fun ページのスクリプトと通信とほかのファイルへのアクセスを許さない() {
        val webView = createSnapshotWebView(context, fileUrl) {}
        val settings = webView.settings

        assertFalse("JavaScript を動かさない", settings.javaScriptEnabled)
        assertTrue("通信を禁止する", settings.blockNetworkLoads)
        assertTrue("画像も通信で読まない", settings.blockNetworkImage)
        assertEquals("キャッシュだけを使う", WebSettings.LOAD_CACHE_ONLY, settings.cacheMode)
        assertFalse(settings.allowFileAccessFromFileURLs)
        assertFalse(settings.allowUniversalAccessFromFileURLs)
        assertFalse(settings.allowContentAccess)
        assertFalse(settings.domStorageEnabled)
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, settings.mixedContentMode)
        // MHTML をファイルから読むために、ファイルの読み込みだけは許す
        assertTrue(settings.allowFileAccess)
    }
}
