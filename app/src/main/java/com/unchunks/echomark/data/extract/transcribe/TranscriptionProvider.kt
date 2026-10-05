package com.unchunks.echomark.data.extract.transcribe

import com.unchunks.echomark.data.extract.media.PcmChunk
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.Locale

/** 音声(16kHz モノラル PCM の1区間)を文字に起こす仕組み。端末内・クラウドの各実装がある。 */
interface TranscriptionProvider {
    /** ログ・[com.unchunks.echomark.domain.extract.ExtractedContent.engine] 用の名前 */
    val name: String

    /**
     * [chunk] を文字に起こす。話していない(無音・音楽だけ)なら空文字。
     * この仕組みが使えない(端末・言語が未対応、通信・API のエラー)ときは null か例外。
     */
    suspend fun transcribe(chunk: PcmChunk): String?
}

/** 文字起こしの結果。 */
data class Transcript(
    val text: String,
    /** 文字起こしできた区間の合計の長さ */
    val transcribedMillis: Long,
    /** 途中で使える仕組みが無くなり、最後まで文字起こしできなかった */
    val incomplete: Boolean,
    /** 使った仕組みの名前(使った順) */
    val engines: List<String>
)

/**
 * 区切った音声を順に文字に起こす。仕組みは [providers] の順に試し(例: クラウド → 端末内)、
 * 失敗した仕組みは以降の区間では使わない(キーの誤り・通信不可・未対応の言語が続けて起きるのを避ける)。
 */
object Transcriber {

    suspend fun transcribe(chunks: List<PcmChunk>, providers: List<TranscriptionProvider>): Transcript? {
        val available = providers.toMutableList()
        val parts = mutableListOf<Pair<Long, String>>()
        val engines = linkedSetOf<String>()
        var transcribedMillis = 0L
        var incomplete = false
        for (chunk in chunks) {
            var text: String? = null
            while (text == null && available.isNotEmpty()) {
                val provider = available.first()
                text = try {
                    provider.transcribe(chunk)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 例外のメッセージに API キーは含まれない(HttpApiSupport で伏せている)が、念のため型だけを残す
                    Timber.w("Transcriber: %s で失敗 (%s)", provider.name, e.javaClass.simpleName)
                    null
                }
                if (text == null) {
                    Timber.i("Transcriber: %s を使えないため、次の仕組みに切り替えます", provider.name)
                    available.removeAt(0)
                } else {
                    engines += provider.name
                }
            }
            if (text == null) {
                incomplete = true
                break
            }
            transcribedMillis += chunk.durationMillis
            if (text.isNotBlank()) parts += chunk.startMillis to text.trim()
        }
        if (parts.isEmpty()) return null
        val body = if (chunks.size > 1) {
            parts.joinToString("\n\n") { (start, text) -> "[${formatTimestamp(start)}]\n$text" }
        } else {
            parts.single().second
        }
        return Transcript(body, transcribedMillis, incomplete, engines.toList())
    }

    /** 区間の開始位置の表示(例: "5:00"、"1:05:00")。 */
    fun formatTimestamp(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
        } else {
            "%d:%02d".format(Locale.ROOT, minutes, seconds)
        }
    }
}
