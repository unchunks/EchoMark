package com.unchunks.echomark.domain.extract

/**
 * 取り出した中身の出どころ。本文の見出し([label])に使う。
 * 見出しの文言を変えると、既に保存した本文の区切りを読めなくなるため変えないこと([ExtractedBody] を参照)。
 */
enum class ExtractionSource(val label: String) {
    /** 画像の OCR と、写っているもの(ラベル)・撮影日時 */
    IMAGE("画像の内容"),

    /** PDF に埋め込まれたテキスト */
    PDF_TEXT("PDF の本文"),

    /** テキストを持たない(スキャンした)PDF のページを画像にして OCR したもの */
    PDF_OCR("PDF から読み取った文字"),

    /** 音声・動画の文字起こし */
    TRANSCRIPT("文字起こし"),

    /** 音声の無い動画の代表フレームを OCR したもの */
    VIDEO_FRAME("動画から読み取った文字"),

    /** テキストファイルの中身 */
    TEXT_FILE("ファイルの本文");

    companion object {
        fun ofLabel(label: String): ExtractionSource? = entries.firstOrNull { it.label == label }
    }
}

/**
 * ファイルから取り出した、要約・検索・チャットに使うテキスト。
 *
 * @property text 取り出したテキスト([MAX_TEXT_LENGTH] 字までに切り詰め済み)
 * @property source 出どころ(本文の見出しになる)
 * @property title ファイル自体が持つタイトル(PDF のメタデータなど)。ブックマークのタイトルがファイル名のままなら置き換えに使える
 * @property pageCount ページ数(PDF)
 * @property durationMs 長さ(音声・動画)
 * @property processedDurationMs 文字起こしした長さ(上限で打ち切ったときは [durationMs] より短い)
 * @property truncated 長すぎて一部だけを取り出した(文字数・ページ数・時間の上限)
 * @property engine 使った仕組み(ログ・表示用。例: "pdfbox", "mlkit", "openai", "on-device")
 */
data class ExtractedContent(
    val text: String,
    val source: ExtractionSource,
    val title: String? = null,
    val pageCount: Int? = null,
    val durationMs: Long? = null,
    val processedDurationMs: Long? = null,
    val truncated: Boolean = false,
    val engine: String? = null
) {
    /** 本文の見出し(例: "読み取った文字")。 */
    val sourceLabel: String get() = source.label

    companion object {
        /** 本文に入れる取り出したテキストの上限(字) */
        const val MAX_TEXT_LENGTH = 50_000

        /**
         * [text] を整えて [MAX_TEXT_LENGTH] 字までに切り詰めた [ExtractedContent] を作る。空なら null。
         * 連続する空行は1つにまとめ、行末の空白を除く。
         */
        fun of(
            text: String,
            source: ExtractionSource,
            title: String? = null,
            pageCount: Int? = null,
            durationMs: Long? = null,
            processedDurationMs: Long? = null,
            truncated: Boolean = false,
            engine: String? = null
        ): ExtractedContent? {
            val normalized = normalizeText(text)
            if (normalized.isBlank()) return null
            val cut = normalized.length > MAX_TEXT_LENGTH
            return ExtractedContent(
                text = if (cut) normalized.take(MAX_TEXT_LENGTH).trimEnd() else normalized,
                source = source,
                title = title?.trim()?.takeIf { it.isNotEmpty() },
                pageCount = pageCount,
                durationMs = durationMs,
                processedDurationMs = processedDurationMs,
                truncated = truncated || cut,
                engine = engine
            )
        }

        /** 改行を \n にそろえ、行末の空白を除き、3行以上の空行を1つの空行にまとめる。 */
        fun normalizeText(text: String): String = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u0000', ' ')
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
