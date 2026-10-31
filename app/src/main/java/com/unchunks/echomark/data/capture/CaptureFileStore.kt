package com.unchunks.echomark.data.capture

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.unchunks.echomark.domain.capture.CaptureFiles
import com.unchunks.echomark.domain.capture.CaptureKind
import com.unchunks.echomark.domain.capture.CaptureTarget
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 撮った・録ったものの一時ファイルを `cacheDir/captures/` に置く。
 * カメラのアプリには FileProvider の content:// の URI を渡して書き込ませる(カメラの権限をこのアプリが持たなくて済む)。
 * 保存すると添付ファイルとしてアプリ内にコピーされるので、ここのファイルは取り込み後に不要になる。
 */
@Singleton
class CaptureFileStore @Inject constructor(
    @ApplicationContext private val context: Context
) : CaptureFiles {

    private val directory: File
        get() = File(context.cacheDir, CAPTURE_DIRECTORY).also { it.mkdirs() }

    override fun create(kind: CaptureKind): CaptureTarget? {
        val now = System.currentTimeMillis()
        val dir = directory
        var sequence = 0
        var file = File(dir, captureFileName(kind, now, ZoneId.systemDefault()))
        // 同じ秒に重なったら番号を付ける
        while (file.exists() && sequence < MAX_SEQUENCE) {
            sequence++
            file = File(dir, captureFileName(kind, now, ZoneId.systemDefault(), sequence))
        }
        return try {
            if (!file.createNewFile()) return null
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            CaptureTarget(uri = uri.toString(), mimeType = kind.mimeType, path = file.path)
        } catch (e: IOException) {
            Timber.w(e, "撮影用の一時ファイルを作れない")
            null
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "撮影用の一時ファイルの URI を作れない")
            null
        }
    }

    override fun hasContent(uri: String): Boolean = fileOf(uri)?.let { it.isFile && it.length() > 0 } ?: false

    override fun discard(uris: Collection<String>) {
        uris.forEach { fileOf(it)?.delete() }
    }

    override fun purgeStale(olderThanMillis: Long) {
        val threshold = System.currentTimeMillis() - olderThanMillis
        directory.listFiles()?.forEach { if (it.isFile && it.lastModified() < threshold) it.delete() }
    }

    /** 一時ファイルの URI から、そのファイルを引く。一時ファイルの置き場所のものでなければ null */
    private fun fileOf(uri: String): File? {
        val parsed: Uri = uri.toUri()
        if (parsed.authority != "${context.packageName}.fileprovider") return null
        val segments = parsed.pathSegments
        // cache-path の name(file_paths.xml)+ ファイル名
        if (segments.size != 2 || segments[0] != CAPTURE_URI_NAME) return null
        val name = segments[1]
        return if (isSafeCaptureName(name)) File(directory, name) else null
    }

    private companion object {
        /** file_paths.xml の cache-path の name */
        const val CAPTURE_URI_NAME = "captures"
        const val MAX_SEQUENCE = 99
    }
}
