package com.unchunks.echomark.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupReadLimitTest {

    @Test
    fun 上限以内ならUTF8の文字列として読む() {
        val text = """{"title":"日本語のタイトル"}"""
        val bytes = text.toByteArray(Charsets.UTF_8)

        assertEquals(text, readBackupText(bytes.inputStream(), maxBytes = bytes.size.toLong()))
    }

    @Test
    fun 上限を超えるファイルは読み込まない() {
        val bytes = ByteArray(1024) { 'a'.code.toByte() }

        val e = assertThrows(BackupFormatException::class.java) {
            readBackupText(bytes.inputStream(), maxBytes = 1023)
        }
        assertTrue(e.message!!.contains("大きすぎ"))
    }

    @Test
    fun 上限は端末のメモリで読み込める大きさにする() {
        assertTrue(MAX_BACKUP_BYTES in (8L * 1024 * 1024)..(64L * 1024 * 1024))
    }
}
