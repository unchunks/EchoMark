package com.unchunks.echomark.domain.video

import java.net.URI

/**
 * PeerTube(だれでも立てられる動画サイトのソフト)の動画ページの URL。
 * サーバーごとにホストが違うため、ホストではなくパスの形(`/w/<短い ID>`・`/videos/watch/<UUID>`・`/videos/embed/<ID>`)で見分ける。
 * 見分けたあとに、同じサーバーの API(`/api/v1/videos/<ID>`)が PeerTube の答えを返すかで確かめる(PeerTubeFetcher)。
 */
object PeerTubeUrls {

    /** @property origin `https://host[:port]`(API を呼ぶ先。ページと同じサーバー) */
    data class VideoRef(val origin: String, val videoId: String)

    fun videoOf(url: String): VideoRef? {
        val uri = try {
            URI(url.trim())
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host ?: return null
        val path = uri.rawPath ?: return null
        val id = VIDEO_PATHS.firstNotNullOfOrNull { it.matchEntire(path)?.groupValues?.get(1) } ?: return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return VideoRef(origin = "$scheme://$host$port", videoId = id)
    }

    /** UUID(36 文字)か、短い UUID(英数字 20〜24 文字。PeerTube 3.3 から) */
    private const val ID = """([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[0-9A-Za-z]{20,24})"""

    private val VIDEO_PATHS = listOf(
        Regex("""/w/$ID/?"""),
        Regex("""/videos/watch/$ID/?"""),
        Regex("""/videos/embed/$ID/?""")
    )
}
