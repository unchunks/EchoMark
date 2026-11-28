package com.unchunks.echomark.data.ai.local

import com.unchunks.components.llm.ondevice.EngineBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlmTimeoutsTest {

    @Test
    fun 部分要約の時間の上限はGPUの方が短い() {
        assertTrue(partTimeoutMillis(EngineBackend.GPU) < partTimeoutMillis(EngineBackend.CPU))
        assertEquals(180_000L, partTimeoutMillis(EngineBackend.GPU))
        assertEquals(300_000L, partTimeoutMillis(EngineBackend.CPU))
    }
}
