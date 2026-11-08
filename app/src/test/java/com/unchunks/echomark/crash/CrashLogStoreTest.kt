package com.unchunks.echomark.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashLogStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L
    private val env = CrashEnvironment(appVersion = "1.2 (3)", device = "Google Pixel 7", osVersion = "Android 16 (API 36)")

    private fun store(maxFiles: Int = 3, maxFileChars: Int = 10_000, dir: File = File(tmp.root, "logs")) =
        CrashLogStore(dir, { env }, maxFiles, maxFileChars) { now++ }

    @Test
    fun 記録にはバージョン_端末_OS_スレッド_スタックトレースが入る() {
        val store = store()
        val name = store.record(CrashLogKind.CRASH, IllegalStateException("boom"), "main")

        assertNotNull(name)
        val text = store.entries().single().text
        assertTrue(text, "バージョン: 1.2 (3)" in text)
        assertTrue("端末: Google Pixel 7" in text)
        assertTrue("OS: Android 16 (API 36)" in text)
        assertTrue("スレッド: main" in text)
        assertTrue("java.lang.IllegalStateException: boom" in text)
        assertTrue("CrashLogStoreTest" in text)
    }

    @Test
    fun 件数の上限を超えたら古いものから消える() {
        val store = store(maxFiles = 3)
        repeat(5) { store.record(CrashLogKind.ERROR, RuntimeException("e$it"), "t") }

        val entries = store.entries()
        assertEquals(3, entries.size)
        // 新しい順。残るのは e4, e3, e2
        assertEquals(listOf("e4", "e3", "e2"), entries.map { Regex("e\\d").find(it.text.substringAfter("----"))!!.value })
    }

    @Test
    fun 長いスタックトレースは上限で切り捨てる() {
        val store = store(maxFileChars = 300)
        store.record(CrashLogKind.CRASH, RuntimeException("x".repeat(5_000)), "main")

        val text = store.entries().single().text
        assertTrue(text.length < 400)
        assertTrue("省略" in text)
    }

    @Test
    fun 同じ時刻に2件書いても上書きしない() {
        val frozen = CrashLogStore(File(tmp.root, "logs"), { env }, 5, 10_000) { 42L }
        frozen.record(CrashLogKind.CRASH, RuntimeException("a"), "t")
        frozen.record(CrashLogKind.CRASH, RuntimeException("b"), "t")

        assertEquals(2, frozen.count())
    }

    @Test
    fun すべて削除できる() {
        val store = store()
        store.record(CrashLogKind.CRASH, RuntimeException(), "t")
        store.record(CrashLogKind.WORKER, RuntimeException(), "t", note = "FooWorker")
        assertEquals(2, store.count())

        store.clear()

        assertEquals(0, store.count())
        assertNull(store.combinedText())
    }

    @Test
    fun まとめた文章は新しい順で補足と種類を含む() {
        val store = store()
        store.record(CrashLogKind.WORKER, RuntimeException("old"), "w", note = "FooWorker")
        store.record(CrashLogKind.CRASH, RuntimeException("new"), "main")

        val text = store.combinedText()!!
        assertTrue(text.indexOf("new") < text.indexOf("old"))
        assertTrue("補足: FooWorker" in text)
        assertTrue("EchoMark ワーカーの失敗" in text)
        assertTrue("EchoMark クラッシュ" in text)
        assertEquals(listOf(CrashLogKind.CRASH, CrashLogKind.WORKER), store.entries().map { it.kind })
    }

    @Test
    fun 書き込めない場所でも例外を投げず_nullを返す() {
        // ディレクトリの場所に通常のファイルがあり、mkdirs も書き込みもできない
        val blocker = tmp.newFile("blocker")
        val store = store(dir = File(blocker, "logs"))

        assertNull(store.record(CrashLogKind.CRASH, RuntimeException(), "main"))
        assertEquals(0, store.count())
    }

    @Test
    fun 関係のないファイルは一覧に出さず_消さない() {
        val dir = File(tmp.root, "logs").apply { mkdirs() }
        val other = File(dir, "memo.txt").apply { writeText("keep") }
        val store = store()
        store.record(CrashLogKind.CRASH, RuntimeException(), "t")

        assertEquals(1, store.count())
        store.clear()
        assertTrue(other.exists())
    }
}
