package com.unchunks.echomark.data.attachment

import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.bookmark.model.AttachmentError
import com.unchunks.echomark.domain.bookmark.model.AttachmentException
import com.unchunks.echomark.domain.bookmark.model.MAX_ATTACHMENT_BYTES
import com.unchunks.echomark.domain.bookmark.model.StoredAttachment
import com.unchunks.echomark.domain.bookmark.model.bookmarkTypeOfMimeType
import com.unchunks.echomark.domain.bookmark.model.normalizeMimeType
import com.unchunks.echomark.domain.repository.AttachmentRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 添付ファイル(画像・PDF・音声・動画・テキスト)をアプリ内(`filesDir/attachments/`)に保存・管理する。
 * - 取り込みは `<UUID>.<拡張子>.part` に書いてから名前を変える(途中で止まっても中途半端なファイルを残さない)
 * - ブックマークからは filesDir からの相対パス(`attachments/<UUID>.<拡張子>`)で参照する
 * - ブックマークを削除してもすぐには消さない(「元に戻す」で戻せるように)。どこからも参照されなくなって
 *   しばらく経ったものを [deleteUnreferenced] で掃除する
 */
@Singleton
class AttachmentStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider
) : AttachmentRepository {

    private val directory: File
        get() = File(context.filesDir, ATTACHMENT_DIRECTORY).also { it.mkdirs() }

    override suspend fun importFile(uri: String): StoredAttachment = importFromUri(uri.toUri())

    /** [uri](content:// など)のファイルをコピーする。失敗は [AttachmentException]。 */
    suspend fun importFromUri(uri: Uri, maxBytes: Long = MAX_ATTACHMENT_BYTES): StoredAttachment =
        withContext(dispatcherProvider.io) {
            val job = currentCoroutineContext().job
            try {
                val (name, size) = queryNameAndSize(uri)
                val mimeType = resolveMimeType(context.contentResolver.getType(uri), name)
                if (bookmarkTypeOfMimeType(mimeType) == null) throw AttachmentException(AttachmentError.Unsupported(mimeType))
                if (size > maxBytes) throw AttachmentException(AttachmentError.TooLarge(maxBytes))
                ensureSpace(size)
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("openInputStream returned null")
                input.use { saveStream(it, mimeType!!, name, maxBytes) { job.isActive } }
            } catch (e: AttachmentException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 読み取り権限の失効(SecurityException)・見つからない(FileNotFoundException)・提供元の不具合など
                Timber.w(e, "ファイルの取り込みに失敗")
                throw AttachmentException(AttachmentError.ReadFailed, e)
            }
        }

    /**
     * [input] を添付ファイルとして保存する(呼び出し元のスレッドで読み書きする)。
     * [maxBytes] を超えたら止めて [AttachmentError.TooLarge]、[isActive] が false になったら止めて取り消す。
     * リンク先のダウンロードでも使う。
     */
    fun saveStream(
        input: InputStream,
        mimeType: String,
        fileName: String?,
        maxBytes: Long,
        isActive: () -> Boolean = { true }
    ): StoredAttachment {
        val type = normalizeMimeType(mimeType)
        if (bookmarkTypeOfMimeType(type) == null) throw AttachmentException(AttachmentError.Unsupported(type))
        val extension = extensionFor(type!!, fileName)
        val storedName = UUID.randomUUID().toString() + (extension?.let { ".$it" } ?: "")
        val part = File(directory, storedName + PART_SUFFIX)
        val dest = File(directory, storedName)
        var copied = 0L
        try {
            FileOutputStream(part).use { out ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    if (!isActive()) throw CancellationException("ファイルの取り込みを中止")
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    if (copied > maxBytes) throw AttachmentException(AttachmentError.TooLarge(maxBytes))
                    out.write(buffer, 0, read)
                }
                out.fd.sync()
            }
            if (copied == 0L) throw AttachmentException(AttachmentError.Empty)
            if (!part.renameTo(dest)) throw IOException("rename failed")
        } catch (e: IOException) {
            part.delete()
            Timber.w(e, "ファイルの書き込みに失敗")
            // 書き込み中の容量不足(サイズが事前に分からないファイルなど)
            val error = if (allocatableBytes() < STORAGE_MARGIN_BYTES) AttachmentError.InsufficientStorage else AttachmentError.ReadFailed
            throw AttachmentException(error, e)
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
        return StoredAttachment(
            filePath = "$ATTACHMENT_DIRECTORY/$storedName",
            mimeType = type,
            fileName = sanitizeDisplayName(fileName, extension),
            fileSize = copied
        )
    }

    /** 保存先のファイル(存在するとは限らない)。添付ファイルの置き場所でないパスなら null */
    fun fileOf(filePath: String?): File? = attachmentFileName(filePath)?.let { File(directory, it) }

    /** 保存してあるファイル。無ければ null */
    fun existingFile(filePath: String?): File? = fileOf(filePath)?.takeIf { it.isFile }

    /** テキストファイルの中身を、先頭から [maxChars] 文字まで読む(UTF-8。読めなければ null) */
    suspend fun readText(filePath: String, maxChars: Int): String? = withContext(dispatcherProvider.io) {
        val file = existingFile(filePath) ?: return@withContext null
        try {
            file.inputStream().use { input ->
                // 先頭の決まった量だけ読む(InputStream.readNBytes は API 33 から)
                val bytes = ByteArray(minOf(file.length(), maxChars.toLong() * MAX_BYTES_PER_CHAR).toInt())
                var filled = 0
                while (filled < bytes.size) {
                    val read = input.read(bytes, filled, bytes.size - filled)
                    if (read < 0) break
                    filled += read
                }
                String(bytes, 0, filled, Charsets.UTF_8).removePrefix("\uFEFF").take(maxChars)
            }
        } catch (e: IOException) {
            Timber.w(e, "テキストファイルを読めない")
            null
        }
    }

    /**
     * 更新日時を今にする。ブックマークを削除したときに呼び、掃除までの猶予を削除の時点から数える
     * (「元に戻す」で戻したときにファイルが残っているように)。
     */
    suspend fun touch(filePath: String) = withContext(dispatcherProvider.io) {
        existingFile(filePath)?.setLastModified(System.currentTimeMillis())
        Unit
    }

    /** 1件を消す */
    suspend fun delete(filePath: String) = withContext(dispatcherProvider.io) {
        fileOf(filePath)?.delete()
        Unit
    }

    /** すべての添付ファイルを消す(全データ削除) */
    suspend fun deleteAll() = withContext(dispatcherProvider.io) {
        directory.listFiles()?.forEach { it.deleteRecursively() }
        Unit
    }

    /** 添付ファイルの合計サイズ(バイト) */
    suspend fun totalBytes(): Long = withContext(dispatcherProvider.io) {
        directory.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }

    /**
     * [referenced](ブックマークが参照している相対パス)に無く、更新日時が [olderThanMillis] より前のファイルを消す。
     * 書き込み途中で残った .part も同じ規則で消す。消した数を返す。
     */
    suspend fun deleteUnreferenced(referenced: Set<String>, olderThanMillis: Long): Int =
        withContext(dispatcherProvider.io) {
            val files = directory.listFiles() ?: return@withContext 0
            files.count { file ->
                val path = "$ATTACHMENT_DIRECTORY/${file.name}"
                file.isFile && path !in referenced && file.lastModified() < olderThanMillis && file.delete()
            }
        }

    /** 表示名とサイズ(不明なら -1)。 */
    private fun queryNameAndSize(uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size = -1L
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        return (name ?: uri.lastPathSegment) to size
    }

    /** 取り込める大きさの空きがあるか確かめる(サイズ不明なら余裕の分だけ) */
    private fun ensureSpace(size: Long) {
        if (allocatableBytes() < size.coerceAtLeast(0L) + STORAGE_MARGIN_BYTES) {
            throw AttachmentException(AttachmentError.InsufficientStorage)
        }
    }

    /** 保存先に確保できる容量。消去可能なキャッシュ分も含めて見積もる。 */
    private fun allocatableBytes(): Long {
        val dir = directory
        return try {
            val storageManager = context.getSystemService(StorageManager::class.java)
            storageManager.getAllocatableBytes(storageManager.getUuidForPath(dir))
        } catch (e: Exception) {
            // 保存先の情報を取れない場合(IOException など)は、通常の空き容量で見積もる
            dir.usableSpace
        }
    }

    companion object {
        private const val PART_SUFFIX = ".part"
        private const val BUFFER_SIZE = 64 * 1024

        /** 取り込んだあとにも残しておく空き容量 */
        private const val STORAGE_MARGIN_BYTES = 50L * 1024 * 1024

        /** UTF-8 の1文字の最大バイト数(読み込む量の見積もり) */
        private const val MAX_BYTES_PER_CHAR = 4

        /**
         * 提供元が教える MIME タイプ(不明・汎用の octet-stream ならファイル名の拡張子から推定)。
         * 決まらなければ null。
         */
        internal fun resolveMimeType(reported: String?, fileName: String?): String? {
            val normalized = normalizeMimeType(reported)
            if (normalized != null && normalized != OCTET_STREAM) return normalized
            val extension = extensionOf(fileName) ?: return normalized
            val fromMap = runCatching { MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) }.getOrNull()
            return normalizeMimeType(fromMap) ?: fallbackMimeTypeOf(extension) ?: normalized
        }

        /** 保存するファイルの拡張子。MIME タイプから決め、分からなければ元のファイル名のものを使う */
        internal fun extensionFor(mimeType: String, fileName: String?): String? {
            val fromMap = runCatching { MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) }.getOrNull()
            return fromMap?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
                ?: fallbackExtensionOf(mimeType)
                ?: extensionOf(fileName)
        }

        private const val OCTET_STREAM = "application/octet-stream"
    }
}
