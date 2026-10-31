package com.unchunks.echomark.di

import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkModuleTest {

    @Test
    fun 資料の検索ではアプリ名と連絡先を名乗る() {
        val userAgents = NetworkModule.provideSearchUserAgents()

        // Wikimedia の User-Agent の方針: アプリ名と連絡先を名乗り、ブラウザの User-Agent を写さない
        assertTrue(userAgents.app.startsWith("EchoMark/"))
        assertTrue(userAgents.app.contains("https://github.com/unchunks/EchoMark"))
        assertTrue(userAgents.browser.startsWith("Mozilla/5.0") && userAgents.browser.contains("EchoMark"))
    }
}
