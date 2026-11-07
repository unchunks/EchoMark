package com.unchunks.echomark.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class FileUiTest {

    @Test
    fun ファイルサイズの表示() {
        assertEquals("0 KB", formatFileSize(0))
        assertEquals("1 KB", formatFileSize(100))
        assertEquals("410 KB", formatFileSize(420_000))
        assertEquals("1.2 MB", formatFileSize(1_300_000))
        assertEquals("530 MB", formatFileSize(556_000_000))
        assertEquals("1.5 GB", formatFileSize(1_610_612_736))
    }
}
