package com.unchunks.echomark.data.ai.local

import com.unchunks.components.llm.ondevice.EngineBackend

/** 長い本文の部分要約1回の時間の上限。GPU は速いので短く、CPU は遅い端末を見込んで長めにする。 */
internal fun partTimeoutMillis(backend: EngineBackend): Long = when (backend) {
    EngineBackend.GPU -> 60 * 1000L
    EngineBackend.CPU -> 90 * 1000L
}
