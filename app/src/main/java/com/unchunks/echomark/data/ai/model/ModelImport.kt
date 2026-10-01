package com.unchunks.echomark.data.ai.model

import java.util.Locale

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
}

/** 取り込みの失敗理由。 */
sealed interface ModelImportError {
    val userMessage: String

    data class UnsupportedFormat(val fileName: String) : ModelImportError {
        override val userMessage =
            "対応していない形式です(${ModelImportValidator.SUPPORTED_EXTENSIONS.joinToString("・")} のみ)"
    }

    data class InsufficientStorage(val requiredBytes: Long, val availableBytes: Long) : ModelImportError {
        override val userMessage = "端末の空き容量が足りません(必要: ${formatBytes(requiredBytes)} / 空き: ${formatBytes(availableBytes)})"
    }

    data object EmptyFile : ModelImportError {
        override val userMessage = "ファイルが空です"
    }

    data object ReadFailed : ModelImportError {
        override val userMessage = "ファイルを読み込めませんでした"
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
    /** MediaPipe tasks-genai(LLM Inference)が読める形式 */
    val SUPPORTED_EXTENSIONS = listOf(".task", ".litertlm")

    /** コピー後にも作業領域が残るよう確保する余白 */
    const val STORAGE_MARGIN_BYTES = 100L * 1024 * 1024

    /** 対応する拡張子(小文字)。非対応なら null。 */
    fun supportedExtension(fileName: String): String? {
        val lower = fileName.lowercase()
        return SUPPORTED_EXTENSIONS.firstOrNull { lower.endsWith(it) }
    }

    /**
     * @param sizeBytes 取り込み元のサイズ。不明なら -1(コピー中に容量不足を検出する)
     * @param usableBytes 保存先の空き容量
     */
    fun validate(fileName: String, sizeBytes: Long, usableBytes: Long): ModelImportError? {
        if (supportedExtension(fileName) == null) return ModelImportError.UnsupportedFormat(fileName)
        if (sizeBytes == 0L) return ModelImportError.EmptyFile
        if (sizeBytes > 0 && sizeBytes + STORAGE_MARGIN_BYTES > usableBytes) {
            return ModelImportError.InsufficientStorage(sizeBytes + STORAGE_MARGIN_BYTES, usableBytes)
        }
        return null
    }
}

/** 1.2 GB / 530 MB のような表示用の文字列。 */
fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format(Locale.ROOT, "%.1f GB", mb / 1024)
    else String.format(Locale.ROOT, "%.0f MB", mb)
}
