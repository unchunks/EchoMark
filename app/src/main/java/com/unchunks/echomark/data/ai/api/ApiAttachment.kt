package com.unchunks.echomark.data.ai.api

import com.unchunks.echomark.domain.provider.ApiProvider
import kotlin.math.roundToInt

/** クラウド API にそのまま渡すファイルの種類。 */
enum class AttachmentKind {
    /** 画像(縮小して JPEG にしたもの) */
    IMAGE,
    PDF,
    AUDIO,
    VIDEO
}

/**
 * クラウド API にテキストと一緒に渡すファイル(base64 にしたもの)。
 * 中身が大きく、ログに出すと読めない・重いため、toString には中身を含めない。
 * @property mimeType 提供元に伝える MIME タイプ([AttachmentPolicy.apiMimeType] でそろえたもの)
 */
class ApiAttachment(
    val kind: AttachmentKind,
    val mimeType: String,
    val base64Data: String,
    val fileName: String
) {
    /** data URL(OpenAI の input_image / input_file で使う)。 */
    fun dataUrl(): String = "data:$mimeType;base64,$base64Data"

    override fun toString(): String = "ApiAttachment(kind=$kind, mimeType=$mimeType, chars=${base64Data.length})"
}

/**
 * どのファイルを、どの提供元に、どの形で渡すかの決まり(端末に依存しない純粋な計算)。
 *
 * 各社の制限(2026-10 時点の公式ドキュメント):
 * - Claude: 画像(JPEG/PNG/GIF/WebP, 1枚 base64 で 10 MB まで、長辺 1568px を超えると縮小して読まれる)、
 *   PDF(リクエスト全体 32 MB・600 ページまで。文脈長 1M 未満のモデルは 100 ページ)。音声・動画の入力は無い
 * - Gemini(generateContent の inlineData): リクエスト全体 20 MB まで。画像・PDF・音声・動画を受け付ける
 * - OpenAI(Responses API): 画像(input_image)、ファイル(input_file, 合計 50 MB)。音声・動画の入力は無い
 *
 * 端末のメモリ(base64 にすると約 1.33 倍)と料金を抑えるため、どの提供元でも各社の上限より小さい
 * [MAX_FILE_BYTES] を上限にする。超えるもの・送れない形式は、端末内で取り出したテキストだけで要約する。
 */
object AttachmentPolicy {

    /** 画像の長辺の上限(px)。Claude の標準の解像度の上限に合わせ、これより大きい画像は縮小して送る */
    const val MAX_IMAGE_LONG_EDGE = 1568

    /** 縮小した画像の JPEG の品質。強く圧縮すると画像内の文字が読みにくくなるため高めにする */
    const val JPEG_QUALITY = 85

    /** 元の画像ファイルの大きさの上限(縮小して送るため大きめ。極端に大きいものは読み込まない) */
    const val MAX_IMAGE_SOURCE_BYTES = 50L * 1024 * 1024

    /** そのまま送るファイル(PDF・音声・動画)の大きさの上限 */
    const val MAX_FILE_BYTES = 10L * 1024 * 1024

    /** そのまま送る PDF のページ数の上限(料金と、文脈長 1M 未満の Claude のモデルの上限に合わせる) */
    const val MAX_PDF_PAGES = 100

    /** MIME タイプからファイルの種類を決める。送れない種類なら null。 */
    fun kindOf(mimeType: String): AttachmentKind? {
        val type = baseType(mimeType)
        return when {
            type == "application/pdf" -> AttachmentKind.PDF
            // SVG はビットマップに変換できないため送らない
            type.startsWith("image/") && type != "image/svg+xml" -> AttachmentKind.IMAGE
            type.startsWith("audio/") -> AttachmentKind.AUDIO
            type.startsWith("video/") -> AttachmentKind.VIDEO
            else -> null
        }
    }

    /** 提供元がテキストと一緒に受け付けるファイルの種類。 */
    fun supportedKinds(provider: ApiProvider): Set<AttachmentKind> = when (provider) {
        ApiProvider.GEMINI -> AttachmentKind.entries.toSet()
        // Claude の Messages API・OpenAI の Responses API には音声・動画の入力が無い
        ApiProvider.CLAUDE, ApiProvider.OPENAI -> setOf(AttachmentKind.IMAGE, AttachmentKind.PDF)
    }

    /**
     * このファイルを [provider] に送るなら、その種類。送らない(非対応の種類・形式、大きすぎる)なら null。
     * PDF のページ数と画像を読めるかは、ファイルを開くとき([AttachmentLoader])に確かめる。
     */
    fun plan(provider: ApiProvider, mimeType: String, sizeBytes: Long): AttachmentKind? {
        val kind = kindOf(mimeType) ?: return null
        if (kind !in supportedKinds(provider)) return null
        if (apiMimeType(kind, mimeType) == null) return null
        val maxBytes = if (kind == AttachmentKind.IMAGE) MAX_IMAGE_SOURCE_BYTES else MAX_FILE_BYTES
        return kind.takeIf { sizeBytes in 1..maxBytes }
    }

    /**
     * 提供元に伝える MIME タイプ。端末が付ける別名(audio/x-m4a など)を各社の一覧の表記にそろえる。
     * 画像は JPEG に変換して送るため常に image/jpeg。一覧に無い音声・動画の形式(AMR など)は null。
     */
    fun apiMimeType(kind: AttachmentKind, mimeType: String): String? {
        val type = baseType(mimeType)
        return when (kind) {
            AttachmentKind.IMAGE -> "image/jpeg"
            AttachmentKind.PDF -> "application/pdf"
            AttachmentKind.AUDIO -> (AUDIO_ALIASES[type] ?: type).takeIf { it in GEMINI_AUDIO_TYPES }
            AttachmentKind.VIDEO -> (VIDEO_ALIASES[type] ?: type).takeIf { it in GEMINI_VIDEO_TYPES }
        }
    }

    /**
     * 画像を読み込むときの間引き(BitmapFactory.Options.inSampleSize)。
     * 長辺が [maxLongEdge] を下回らない範囲で最大の 2 のべき乗にする(最後の縮小は読み込み後に行う)。
     */
    fun inSampleSize(width: Int, height: Int, maxLongEdge: Int = MAX_IMAGE_LONG_EDGE): Int {
        val longEdge = maxOf(width, height)
        var sample = 1
        while (longEdge / (sample * 2) >= maxLongEdge) sample *= 2
        return sample
    }

    /** 縦横比を保ったまま、長辺を [maxLongEdge] 以下にした大きさ(小さい画像は拡大しない)。 */
    fun scaledSize(width: Int, height: Int, maxLongEdge: Int = MAX_IMAGE_LONG_EDGE): Pair<Int, Int> {
        val longEdge = maxOf(width, height)
        if (longEdge <= maxLongEdge) return width to height
        val scale = maxLongEdge / longEdge.toDouble()
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    /** プロンプトでのファイルの呼び名。 */
    fun label(kind: AttachmentKind): String = when (kind) {
        AttachmentKind.IMAGE -> "画像"
        AttachmentKind.PDF -> "PDF"
        AttachmentKind.AUDIO -> "音声"
        AttachmentKind.VIDEO -> "動画"
    }

    private fun baseType(mimeType: String): String = mimeType.substringBefore(';').trim().lowercase()

    /** Gemini が受け付ける音声の形式(https://ai.google.dev/gemini-api/docs/audio) */
    private val GEMINI_AUDIO_TYPES = setOf(
        "audio/wav", "audio/mp3", "audio/aiff", "audio/aac", "audio/ogg", "audio/flac",
        "audio/mpeg", "audio/m4a", "audio/opus", "audio/webm"
    )

    /** Gemini が受け付ける動画の形式(https://ai.google.dev/gemini-api/docs/video-understanding) */
    private val GEMINI_VIDEO_TYPES = setOf(
        "video/mp4", "video/mpeg", "video/mov", "video/avi", "video/x-flv",
        "video/mpg", "video/webm", "video/wmv", "video/3gpp"
    )

    private val AUDIO_ALIASES = mapOf(
        "audio/x-wav" to "audio/wav",
        "audio/wave" to "audio/wav",
        "audio/x-aiff" to "audio/aiff",
        "audio/x-aac" to "audio/aac",
        "audio/x-flac" to "audio/flac",
        "audio/mp4" to "audio/m4a",
        "audio/x-m4a" to "audio/m4a",
        "audio/mp4a-latm" to "audio/m4a"
    )

    private val VIDEO_ALIASES = mapOf(
        "video/quicktime" to "video/mov",
        "video/x-msvideo" to "video/avi",
        "video/x-ms-wmv" to "video/wmv",
        "video/3gp" to "video/3gpp"
    )
}
