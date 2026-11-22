package com.unchunks.echomark.data.video

import android.content.Context
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.components.filestorage.allocatableBytes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * オフライン用に保存した動画のファイルの置き場所(`filesDir/offline_videos/`)。
 * 添付ファイル(`attachments/`)とは分ける(大きく、バックアップの zip・Android のバックアップに入れないため。
 * `backup_rules.xml`・`data_extraction_rules.xml` で外している)。
 * - ダウンロードの途中は `<ブックマークの ID>.part` に書く(止まっても続きから落とせるよう、ブックマークごとに決まった名前)
 * - 落とし終えたら `<ブックマークの ID>-<UUID>.<拡張子>` に名前を変え、相対パス(`offline_videos/…`)で記録する
 */
@Singleton
class VideoFileStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider
) {
    private val filesDir: File get() = context.filesDir

    val directory: File
        get() = File(filesDir, DIRECTORY).also { it.mkdirs() }

    /** ダウンロードの途中のファイル */
    fun partFile(bookmarkId: Long): File = File(directory, "$bookmarkId$PART_SUFFIX")

    /** 落とし終えた [part] を保存先へ移し、相対パスを返す */
    fun adopt(part: File, bookmarkId: Long, mimeType: String): String {
        val name = "$bookmarkId-${UUID.randomUUID()}.${extensionFor(mimeType)}"
        val dest = File(directory, name)
        if (!part.renameTo(dest)) throw java.io.IOException("rename failed")
        return "$DIRECTORY/$name"
    }

    /** 保存先のファイル(存在するとは限らない)。保存先でないパスなら null */
    fun fileOf(path: String?): File? = videoFile(filesDir, path)

    fun existingFile(path: String?): File? = fileOf(path)?.takeIf { it.isFile }

    suspend fun delete(path: String) = withContext(dispatcherProvider.io) {
        fileOf(path)?.delete()
        Unit
    }

    suspend fun deletePart(bookmarkId: Long) = withContext(dispatcherProvider.io) {
        partFile(bookmarkId).delete()
        Unit
    }

    /** すべての動画のファイルを消す(途中のものも) */
    suspend fun deleteAll() = withContext(dispatcherProvider.io) {
        directory.listFiles()?.forEach { it.deleteRecursively() }
        Unit
    }

    /** 置き場所の合計(途中のものも含む) */
    suspend fun totalBytes(): Long = withContext(dispatcherProvider.io) {
        directory.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }

    /** 端末の空き容量(この置き場所に書ける量) */
    fun freeBytes(): Long = context.allocatableBytes(directory)

    /**
     * 参照されていないファイルを消す。落とし終えたファイルは [referenced] に無く [olderThanMillis] より前に更新されたもの、
     * 途中のファイルは、ブックマーク([existingBookmarkIds])が無いか、[partOlderThanMillis] より前から止まっているもの。
     */
    suspend fun deleteUnreferenced(
        referenced: Set<String>,
        existingBookmarkIds: Set<Long>,
        olderThanMillis: Long,
        partOlderThanMillis: Long
    ): Int = withContext(dispatcherProvider.io) {
        var count = 0
        directory.listFiles()?.filter { it.isFile }?.forEach { file ->
            val stale = if (file.name.endsWith(PART_SUFFIX)) {
                val id = file.name.removeSuffix(PART_SUFFIX).toLongOrNull()
                id == null || id !in existingBookmarkIds || file.lastModified() < partOlderThanMillis
            } else {
                "$DIRECTORY/${file.name}" !in referenced && file.lastModified() < olderThanMillis
            }
            if (stale && file.delete()) count++
        }
        count
    }

    companion object {
        const val DIRECTORY = "offline_videos"
        private const val PART_SUFFIX = ".part"

        /** MIME タイプから拡張子(分からなければ mp4) */
        internal fun extensionFor(mimeType: String): String = when (mimeType.substringBefore(';').trim().lowercase()) {
            "video/webm" -> "webm"
            "video/quicktime" -> "mov"
            "video/x-matroska" -> "mkv"
            "video/ogg" -> "ogv"
            "video/x-m4v" -> "m4v"
            else -> "mp4"
        }
    }
}

/**
 * 動画の保存先の相対パス [path] のファイル。`offline_videos/` の直下のファイル名だけを受け付ける
 * (`..` などで置き場所の外を指すパスは null)。
 */
internal fun videoFile(filesDir: File, path: String?): File? {
    val name = path?.removePrefix("${VideoFileStore.DIRECTORY}/")?.takeIf { it != path } ?: return null
    if (name.isEmpty() || name.contains('/') || name.contains('\\') || name == "." || name == "..") return null
    return File(File(filesDir, VideoFileStore.DIRECTORY), name)
}
