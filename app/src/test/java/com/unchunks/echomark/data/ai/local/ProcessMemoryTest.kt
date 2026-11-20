package com.unchunks.echomark.data.ai.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessMemoryTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun VmRSSをバイトで読む() {
        val status = "Name:\techomark\nVmPeak:\t 9000 kB\nVmRSS:\t    2048 kB\nThreads:\t4\n"

        assertEquals(2048L * 1024, ProcessMemory.parseResidentBytes(status))
        assertNull(ProcessMemory.parseResidentBytes("Name:\techomark\n"))
    }

    @Test
    fun 動かしている間に増えた量の最大を返す() {
        val samples = ArrayDeque(listOf(1_000L, 1_500L, 1_200L))

        val (result, increase) = ProcessMemory.measurePeakIncrease(scope, read = { samples.removeFirstOrNull() }) { "結果" }
        scope.cancel()

        assertEquals("結果", result)
        assertEquals(500L, increase)
    }

    @Test
    fun メモリを読めなくても結果は返す() {
        val (result, increase) = ProcessMemory.measurePeakIncrease(scope, read = { null }) { "結果" }
        scope.cancel()

        assertEquals("結果", result)
        assertNull(increase)
    }
}
