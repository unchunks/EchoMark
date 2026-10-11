package com.unchunks.echomark.ui.common

import java.net.URI

private val URL_IN_TEXT = Regex("""https?://[^\s<>"']+""")
private const val TRAILING_PUNCTUATION = ".,;:!?)]}」』）、。"

/**
 * 入力された文字列を保存できる URL に整える。整えられなければ null。
 * - 前後の空白を除き、スキームが無ければ https:// を補う(「example.com/a」→「https://example.com/a」)
 * - http/https 以外、ホストに「.」が無いもの(「hello」など)、空白を含むものは URL とみなさない
 */
fun normalizeUrlInput(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
    val hasScheme = trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    if (!hasScheme && "://" in trimmed) return null
    val candidate = if (hasScheme) trimmed else "https://$trimmed"
    val host = try {
        URI(candidate).host
    } catch (e: Exception) {
        null
    } ?: return null
    return candidate.takeIf { '.' in host && !host.startsWith('.') && !host.endsWith('.') }
}

/** 文章の中から最初の http/https の URL を取り出す(末尾の句読点・閉じ括弧は除く)。見つからなければ null。 */
fun findUrlInText(text: String): String? =
    URL_IN_TEXT.find(text)?.value
        ?.trimEnd { it in TRAILING_PUNCTUATION }
        ?.takeIf { it.length > "https://".length }
