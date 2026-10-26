package com.unchunks.echomark.data.ai.local

import com.unchunks.components.llm.ondevice.EngineBackend

/**
 * 長い本文の部分要約1回の時間の上限。GPU は速いので短く、CPU は遅い端末を見込んで長めにする。
 * 遅い端末(Snapdragon 665 で Qwen3 0.6B)では、2,000 文字の部分要約に CPU で約 3 分かかる
 * (プロンプトの読み込み約 80 秒 + 300 文字のメモの出力約 100 秒)。繰り返しは出力の文字数の上限で止めるため、長めにしてよい。
 */
internal fun partTimeoutMillis(backend: EngineBackend): Long = when (backend) {
    EngineBackend.GPU -> 3 * 60 * 1000L
    EngineBackend.CPU -> 5 * 60 * 1000L
}
