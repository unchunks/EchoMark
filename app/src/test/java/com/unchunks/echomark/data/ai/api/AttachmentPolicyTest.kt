package com.unchunks.echomark.data.ai.api

import com.unchunks.echomark.domain.provider.ApiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** どのファイルをどの提供元に送るか、画像をどこまで縮小するか。 */
class AttachmentPolicyTest {

    private val mb = 1024L * 1024

    @Test
    fun 種類はMIMEタイプから決める() {
        assertEquals(AttachmentKind.IMAGE, AttachmentPolicy.kindOf("image/png"))
        assertEquals(AttachmentKind.PDF, AttachmentPolicy.kindOf("application/pdf"))
        assertEquals(AttachmentKind.AUDIO, AttachmentPolicy.kindOf("audio/mp4"))
        assertEquals(AttachmentKind.VIDEO, AttachmentPolicy.kindOf("Video/MP4; codecs=avc1"))
        assertNull(AttachmentPolicy.kindOf("image/svg+xml"))
        assertNull(AttachmentPolicy.kindOf("text/plain"))
    }

    @Test
    fun ClaudeとOpenAIは画像とPDFだけ_Geminiは音声と動画も送る() {
        for (provider in listOf(ApiProvider.CLAUDE, ApiProvider.OPENAI)) {
            assertEquals(AttachmentKind.IMAGE, AttachmentPolicy.plan(provider, "image/heic", 3 * mb))
            assertEquals(AttachmentKind.PDF, AttachmentPolicy.plan(provider, "application/pdf", 3 * mb))
            assertNull(AttachmentPolicy.plan(provider, "audio/mpeg", 3 * mb))
            assertNull(AttachmentPolicy.plan(provider, "video/mp4", 3 * mb))
        }
        assertEquals(AttachmentKind.AUDIO, AttachmentPolicy.plan(ApiProvider.GEMINI, "audio/mpeg", 3 * mb))
        assertEquals(AttachmentKind.VIDEO, AttachmentPolicy.plan(ApiProvider.GEMINI, "video/mp4", 3 * mb))
    }

    @Test
    fun 大きすぎるファイルは送らない() {
        val limit = AttachmentPolicy.MAX_FILE_BYTES
        assertEquals(AttachmentKind.PDF, AttachmentPolicy.plan(ApiProvider.CLAUDE, "application/pdf", limit))
        assertNull(AttachmentPolicy.plan(ApiProvider.CLAUDE, "application/pdf", limit + 1))
        assertNull(AttachmentPolicy.plan(ApiProvider.GEMINI, "video/mp4", limit + 1))
        assertNull(AttachmentPolicy.plan(ApiProvider.GEMINI, "audio/mpeg", 0))
        // 画像は縮小して送るため、元のファイルは大きくてもよい
        assertEquals(AttachmentKind.IMAGE, AttachmentPolicy.plan(ApiProvider.CLAUDE, "image/jpeg", limit * 2))
        assertNull(AttachmentPolicy.plan(ApiProvider.CLAUDE, "image/jpeg", AttachmentPolicy.MAX_IMAGE_SOURCE_BYTES + 1))
    }

    @Test
    fun 端末のMIMEタイプの別名を各社の表記にそろえ_一覧に無い形式は送らない() {
        assertEquals("audio/m4a", AttachmentPolicy.apiMimeType(AttachmentKind.AUDIO, "audio/mp4"))
        assertEquals("audio/m4a", AttachmentPolicy.apiMimeType(AttachmentKind.AUDIO, "audio/x-m4a"))
        assertEquals("audio/wav", AttachmentPolicy.apiMimeType(AttachmentKind.AUDIO, "audio/x-wav"))
        assertEquals("video/mov", AttachmentPolicy.apiMimeType(AttachmentKind.VIDEO, "video/quicktime"))
        assertEquals("video/3gpp", AttachmentPolicy.apiMimeType(AttachmentKind.VIDEO, "video/3gpp"))
        // 画像は JPEG に変換して送る
        assertEquals("image/jpeg", AttachmentPolicy.apiMimeType(AttachmentKind.IMAGE, "image/png"))
        assertNull(AttachmentPolicy.apiMimeType(AttachmentKind.AUDIO, "audio/amr"))
        assertNull(AttachmentPolicy.plan(ApiProvider.GEMINI, "audio/amr", mb))
    }

    @Test
    fun 間引きは長辺が上限を下回らない最大の2のべき乗() {
        assertEquals(1, AttachmentPolicy.inSampleSize(1000, 800))
        assertEquals(1, AttachmentPolicy.inSampleSize(3000, 2000))
        assertEquals(2, AttachmentPolicy.inSampleSize(4000, 3000))
        assertEquals(2, AttachmentPolicy.inSampleSize(3000, 6271))
        assertEquals(4, AttachmentPolicy.inSampleSize(8000, 6000))
    }

    @Test
    fun 縮小は縦横比を保って長辺を上限にする() {
        assertEquals(800 to 600, AttachmentPolicy.scaledSize(800, 600))
        assertEquals(1568 to 1176, AttachmentPolicy.scaledSize(2000, 1500))
        assertEquals(882 to 1568, AttachmentPolicy.scaledSize(1080, 1920))
        assertEquals(1568 to 1, AttachmentPolicy.scaledSize(20000, 2))
    }
}
