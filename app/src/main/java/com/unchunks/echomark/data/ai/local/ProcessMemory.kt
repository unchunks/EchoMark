package com.unchunks.echomark.data.ai.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * アプリのプロセスが使っているメモリ(`/proc/self/status` の VmRSS)。端末内モデルの測定で、増えた量の目安を見るために読む。
 * モデルは主にファイルの読み込み(mmap)とネイティブのメモリで動くため、Java のヒープの量では分からない。
 */
internal object ProcessMemory {
    private const val SAMPLE_INTERVAL_MS = 200L
    private const val BYTES_PER_KB = 1024L
    private val VM_RSS = Regex("""^VmRSS:\s+(\d+)\s+kB""", RegexOption.MULTILINE)

    /** `/proc/self/status` の内容から VmRSS を読む。無ければ null。 */
    fun parseResidentBytes(status: String): Long? =
        VM_RSS.find(status)?.groupValues?.get(1)?.toLongOrNull()?.times(BYTES_PER_KB)

    /** 今のプロセスのメモリ。読めなければ null。 */
    fun residentBytes(status: File = File("/proc/self/status")): Long? = try {
        parseResidentBytes(status.readText())
    } catch (e: IOException) {
        Timber.d(e, "プロセスのメモリを読めない")
        null
    }

    /**
     * [block] を動かしている間のメモリを [scope] で定期的に読み、[block] の結果と、始めたときから増えた量の最大を返す。
     * 読めなければ増えた量は null。
     */
    fun <T> measurePeakIncrease(scope: CoroutineScope, read: () -> Long? = { residentBytes() }, block: () -> T): Pair<T, Long?> {
        val baseline = read() ?: return block() to null
        val peak = AtomicLong(baseline)
        val sampling = scope.launch {
            while (isActive) {
                read()?.let { sample -> peak.accumulateAndGet(sample) { a, b -> maxOf(a, b) } }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
        try {
            val result = block()
            read()?.let { sample -> peak.accumulateAndGet(sample) { a, b -> maxOf(a, b) } }
            return result to peak.get() - baseline
        } finally {
            sampling.cancel()
        }
    }
}
