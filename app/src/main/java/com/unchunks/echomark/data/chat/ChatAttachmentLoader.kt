package com.unchunks.echomark.data.chat

import com.unchunks.echomark.data.attachment.AttachmentStore
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.bookmark.model.AttachmentException
import com.unchunks.echomark.domain.bookmark.model.StoredAttachment
import com.unchunks.echomark.domain.bookmark.model.contentKindOfMimeType
import com.unchunks.echomark.domain.extract.ContentExtractor
import com.unchunks.echomark.domain.model.ChatAttachmentContext
import com.unchunks.echomark.domain.model.ChatAttachmentException
import com.unchunks.echomark.domain.repository.ChatAttachmentSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * チャットに添付されたファイルを、質問の根拠にする本文にする。ファイルはブックマークと同じ保存先へ取り込み、
 * 本文は既存の取り出し(テキスト・PDF・画像の文字・動画や音声の文字起こし)を使う。端末内で完結する。
 */
class ChatAttachmentLoader @Inject constructor(
    private val store: AttachmentStore,
    private val extractor: ContentExtractor,
    private val dispatcherProvider: DispatcherProvider
) : ChatAttachmentSource {
    /**
     * @throws ChatAttachmentException 取り込めないとき、または本文が取り出せなかったとき(画面にそのまま出せる文言)
     */
    override suspend fun load(uri: String): ChatAttachmentContext = withContext(dispatcherProvider.io) {
        val stored = try {
            store.importFile(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AttachmentException) {
            throw ChatAttachmentException(e.error.userMessage)
        }
        val text = textOf(stored)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ChatAttachmentException("「${stored.fileName}」の中身を読み取れませんでした")
        ChatAttachmentContext(name = stored.fileName, text = text.take(MAX_CHARS))
    }

    private suspend fun textOf(stored: StoredAttachment): String? {
        if (stored.mimeType.startsWith("text/")) return store.readText(stored.filePath, MAX_CHARS)
        val kind = contentKindOfMimeType(stored.mimeType) ?: return null
        val file = store.fileOf(stored.filePath) ?: return null
        return extractor.extract(file, stored.mimeType, kind)?.text
    }

    companion object {
        /** 質問の根拠に入れる本文の文字数の上限(端末内 AI の文脈の予算に収まる量) */
        const val MAX_CHARS = 3_000
    }
}
