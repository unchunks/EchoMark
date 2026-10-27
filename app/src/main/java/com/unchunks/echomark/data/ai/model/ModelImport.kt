package com.unchunks.echomark.data.ai.model

import com.unchunks.components.filestorage.formatBytes

/** 取り込み済みのオンデバイス LLM モデル。 */
data class LocalModelInfo(
    /** filesDir/models/ 内の保存ファイル名 */
    val fileName: String,
    /** 取り込み元のファイル名(表示用) */
    val displayName: String,
    val sizeBytes: Long,
    /** 取り込んだ日時(epoch millis) */
    val importedAt: Long
) {
    /** Gemma 系ならチャットテンプレート(<start_of_turn> 等)を明示的に指定する。 */
    val isGemma: Boolean get() = displayName.contains("gemma", ignoreCase = true)

    /**
     * MediaPipe の LLM Inference 向けの旧形式(.task)か。LiteRT-LM でも読めるが、会話の書式がファイルに入っていない
     * ものや、LiteRT-LM で動かないものがあるため、画面で .litertlm 版の取り込み直しを勧める。
     */
    val isLegacyTaskFormat: Boolean get() = fileName.endsWith(ModelImportValidator.LEGACY_TASK_EXTENSION, ignoreCase = true)
}

/**
 * 端末内モデルを読み込めなかったこと(LiteRT-LM のエンジンを GPU でも CPU でも作れなかった)。
 * 取り込み直すか削除するまで、設定画面に出して取り込み直しを促す。
 * @param model 読み込めなかったモデル(取り込み直した後のモデルには出さない)
 */
data class LocalModelLoadFailure(val model: LocalModelInfo, val detail: String?) {
    val userMessage: String
        get() = if (model.isLegacyTaskFormat) {
            "このモデル(旧形式の .task)を読み込めませんでした。同じモデルの .litertlm 版を取り込み直してください"
        } else {
            "このモデルを読み込めませんでした。ファイルが壊れているか、この端末・アプリでは動かないモデルです。別のモデルを取り込み直してください"
        }
}

/** 取り込みの失敗理由。 */
sealed interface ModelImportError {
    val userMessage: String

    data class UnsupportedFormat(
        val fileName: String,
        val extensions: List<String> = ModelImportValidator.SUPPORTED_EXTENSIONS
    ) : ModelImportError {
        override val userMessage = "対応していない形式です(${extensions.joinToString("・")} のみ)"
    }

    data class InsufficientStorage(val requiredBytes: Long, val availableBytes: Long) : ModelImportError {
        override val userMessage = "端末の空き容量が足りません(必要: ${formatBytes(requiredBytes)} / 空き: ${formatBytes(availableBytes)})"
    }

    data object EmptyFile : ModelImportError {
        override val userMessage = "ファイルが空です"
    }

    /** 取り込み時に選んだモデルの種類が、対応している一覧に無い */
    data object UnknownModelProfile : ModelImportError {
        override val userMessage = "対応していない種類のモデルです"
    }

    data object ReadFailed : ModelImportError {
        override val userMessage = "ファイルを読み込めませんでした"
    }

    /** 拡張子は対応しているが、中身がその形式ではない(名前を変えた別のファイル・途中で切れたダウンロードなど) */
    data class InvalidModelFile(val extension: String) : ModelImportError {
        override val userMessage = "$extension 形式のモデルファイルではありません(ダウンロードが途中で切れていないか確かめてください)"
    }
}

/** 取り込み処理の状態。 */
sealed interface ModelImportState {
    data object Idle : ModelImportState

    /** コピー中。[totalBytes] が不明(-1)なら進捗率は出せない。 */
    data class Copying(val copiedBytes: Long, val totalBytes: Long) : ModelImportState {
        val fraction: Float? get() = if (totalBytes > 0) (copiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
    }

    data class Succeeded(val model: LocalModelInfo) : ModelImportState
    data class Failed(val error: ModelImportError) : ModelImportState
}

/** 取り込み前の検証。Android に依存しない純粋な関数で、単体テストできる。 */
object ModelImportValidator {
    /** LiteRT-LM の形式(推奨) */
    const val LITERT_LM_EXTENSION = ".litertlm"

    /** MediaPipe の LLM Inference 向けの旧形式。LiteRT-LM でも読める */
    const val LEGACY_TASK_EXTENSION = ".task"

    /** 端末内 AI(LiteRT-LM)が読める形式 */
    val SUPPORTED_EXTENSIONS = listOf(LITERT_LM_EXTENSION, LEGACY_TASK_EXTENSION)

    /** 埋め込みモデル(MediaPipe TextEmbedder)が読める形式 */
    val EMBEDDING_EXTENSIONS = listOf(".task", ".tflite")

    /** コピー後にも作業領域が残るよう確保する余白 */
    const val STORAGE_MARGIN_BYTES = 100L * 1024 * 1024

    /** 対応する拡張子(小文字)。非対応なら null。 */
    fun supportedExtension(fileName: String, extensions: List<String> = SUPPORTED_EXTENSIONS): String? {
        val lower = fileName.lowercase()
        return extensions.firstOrNull { lower.endsWith(it) }
    }

    /**
     * @param sizeBytes 取り込み元のサイズ。不明なら -1(コピー中に容量不足を検出する)
     * @param usableBytes 保存先の空き容量
     */
    fun validate(
        fileName: String,
        sizeBytes: Long,
        usableBytes: Long,
        extensions: List<String> = SUPPORTED_EXTENSIONS
    ): ModelImportError? {
        if (supportedExtension(fileName, extensions) == null) return ModelImportError.UnsupportedFormat(fileName, extensions)
        if (sizeBytes == 0L) return ModelImportError.EmptyFile
        if (sizeBytes > 0 && sizeBytes + STORAGE_MARGIN_BYTES > usableBytes) {
            return ModelImportError.InsufficientStorage(sizeBytes + STORAGE_MARGIN_BYTES, usableBytes)
        }
        return null
    }

    /** 中身の形式を見分けるのに読む、ファイルの先頭のバイト数 */
    const val HEADER_BYTES = 8

    /**
     * ファイルの先頭([header])が、拡張子([extension])の形式のものか。LiteRT-LM と同じ見分け方をする
     * (.litertlm は先頭が「LITERTLM」、.task は ZIP で先頭が「PK」)。対応していない拡張子なら true(調べない)。
     */
    fun matchesFormat(extension: String, header: ByteArray): Boolean {
        val text = String(header.copyOf(minOf(header.size, HEADER_BYTES)), Charsets.ISO_8859_1)
        return when (extension.lowercase()) {
            LITERT_LM_EXTENSION -> text.startsWith("LITERTLM")
            LEGACY_TASK_EXTENSION -> text.startsWith("PK")
            else -> true
        }
    }
}
