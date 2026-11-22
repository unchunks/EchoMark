package com.unchunks.echomark.data.remote

/**
 * 字幕のファイル(WebVTT・SubRip)を、時刻を除いた文章にする(純粋関数)。
 * 見出し(WEBVTT)・NOTE / STYLE / REGION のブロック・番号の行・時刻の行・タグ(<c> や <00:00:01.000> など)を除き、
 * 続けて同じ行(表示し直しで重なる字幕)は1つにする。
 */
object CaptionFileText {

    fun parse(text: String): String {
        val lines = mutableListOf<String>()
        var skippingBlock = false
        for (raw in text.removePrefix(BOM).lines()) {
            val line = raw.trim()
            if (line.isEmpty()) {
                skippingBlock = false
                continue
            }
            if (skippingBlock) continue
            when {
                line.startsWith("WEBVTT") -> skippingBlock = true
                line == "NOTE" || line.startsWith("NOTE ") || line == "STYLE" || line == "REGION" -> skippingBlock = true
                line.contains("-->") -> Unit
                CUE_NUMBER.matches(line) -> Unit
                else -> {
                    val cleaned = decodeEntities(TAG.replace(line, "")).trim()
                    if (cleaned.isNotEmpty() && lines.lastOrNull() != cleaned) lines += cleaned
                }
            }
        }
        return lines.joinToString("\n")
    }

    private fun decodeEntities(text: String): String =
        text.replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&amp;", "&")

    /** 先頭の BOM(文字コードの印) */
    private val BOM = 0xFEFF.toChar().toString()

    private val CUE_NUMBER = Regex("""\d+""")
    private val TAG = Regex("""<[^>]*>""")
}
