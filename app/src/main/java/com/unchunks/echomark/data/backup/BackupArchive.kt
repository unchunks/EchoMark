package com.unchunks.echomark.data.backup

import com.unchunks.echomark.data.attachment.isValidAttachmentPath
import java.io.EOFException
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 「ファイルも含めて書き出す」バックアップ(zip)。Android に依存しない純粋な処理で、単体テストできる。
 *
 * 形式:
 * - `backup.json`: [BackupJson] と同じ JSON(先頭に入れる)
 * - `attachments/<ファイル名>`: 添付ファイル・オフラインコピーの本体。名前は DB の filePath・snapshotPath・snapshotPdfPath と同じ
 *   (ファイル名は UUID なので、別の端末のファイルとぶつからない)
 *
 * 読み込みでは、名前が決まった形でないもの(`../` を含むものなど)・同じ名前の重なり・大きすぎるものがあれば、何も取り込まずに断る。
 */
object BackupArchive {
    const val JSON_ENTRY = "backup.json"

    /** zip のローカルファイルヘッダーの印(PK\u0003\u0004) */
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /** 先頭の数バイトが zip の印か(JSON のバックアップと見分ける) */
    fun isZip(header: ByteArray): Boolean =
        header.size >= ZIP_MAGIC.size && ZIP_MAGIC.indices.all { header[it] == ZIP_MAGIC[it] }

    /**
     * [data] を JSON として先頭に入れ、続けて [files](添付ファイルの相対パス → ファイル)を入れる。[output] は閉じない。
     * 写真・動画などはもとから圧縮されているため、ファイルは圧縮せずに入れる(書き出しを速くする)。
     */
    fun write(data: BackupData, files: Map<String, File>, output: OutputStream) {
        val zip = ZipOutputStream(NonClosingOutputStream(output))
        zip.putNextEntry(ZipEntry(JSON_ENTRY))
        val writer = zip.bufferedWriter(Charsets.UTF_8)
        BackupJson.write(data, writer)
        writer.flush()
        zip.closeEntry()
        for ((path, file) in files.toSortedMap()) {
            require(isValidAttachmentPath(path)) { "unsafe path: $path" }
            zip.putNextEntry(storedEntry(path, file))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
    }

    /** 圧縮しない(STORED)エントリ。STORED は書く前に大きさと CRC が要る */
    private fun storedEntry(path: String, file: File): ZipEntry {
        val crc = java.util.zip.CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                crc.update(buffer, 0, read)
            }
        }
        return ZipEntry(path).apply {
            method = ZipEntry.STORED
            size = file.length()
            compressedSize = file.length()
            this.crc = crc.value
        }
    }

    /** 展開した結果。[files] は添付ファイルの相対パス → [stagingDir] の中に展開したファイル */
    class Staged(val jsonText: String, val files: Map<String, File>)

    /**
     * [input] の zip を [stagingDir](空のディレクトリ)に展開する。決まった形でないものがあれば [BackupFormatException]
     * (展開した分は呼び出し側が [stagingDir] ごと消す)。
     * @param maxTotalBytes 展開するファイルの合計の上限(空き容量から決める。zip 爆弾で端末を埋めない)
     */
    fun extract(
        input: InputStream,
        stagingDir: File,
        maxJsonBytes: Long,
        maxFileBytes: Long,
        maxTotalBytes: Long,
        maxEntries: Int = MAX_ENTRIES
    ): Staged {
        var json: String? = null
        val files = LinkedHashMap<String, File>()
        var total = 0L
        var entries = 0
        try {
            val zip = ZipInputStream(input)
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > maxEntries) throw BackupFormatException("ファイルの数が多すぎます(上限 $maxEntries)")
                val name = entry.name
                when {
                    name == JSON_ENTRY -> {
                        if (json != null) throw BackupFormatException("バックアップの中身が2つあります")
                        json = readBackupText(zip, maxJsonBytes)
                    }
                    isValidAttachmentPath(name) && !entry.isDirectory -> {
                        if (name in files) throw BackupFormatException("同じ名前のファイルが2つあります")
                        val dest = File(stagingDir, name.substringAfter('/'))
                        val written = copyCapped(zip, dest, maxFileBytes, maxTotalBytes - total)
                        total += written
                        files[name] = dest
                    }
                    else -> throw BackupFormatException("EchoMark のバックアップに無いファイルが入っています")
                }
                zip.closeEntry()
            }
        } catch (e: ZipException) {
            throw BackupFormatException(BROKEN_ZIP_MESSAGE, e)
        } catch (e: EOFException) {
            // 途中で切れた zip
            throw BackupFormatException(BROKEN_ZIP_MESSAGE, e)
        }
        return Staged(json ?: throw BackupFormatException("zip の中にバックアップ(backup.json)がありません"), files)
    }

    /** [input] の今のエントリを [dest] に書く。[maxBytes]・[remaining] を超えたら [BackupFormatException] */
    private fun copyCapped(input: InputStream, dest: File, maxBytes: Long, remaining: Long): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var written = 0L
        dest.outputStream().use { out ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                written += read
                if (written > maxBytes) throw BackupFormatException("大きすぎるファイルが入っています")
                if (written > remaining) throw BackupFormatException("端末の空き容量が足りません。空きを作ってからもう一度お試しください")
                out.write(buffer, 0, read)
            }
        }
        return written
    }

    /** 書き出し先の stream を閉じない(閉じるのは呼び出し側) */
    private class NonClosingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = flush()
    }

    private const val BROKEN_ZIP_MESSAGE = "zip ファイルを読み取れませんでした(壊れている可能性があります)"

    private const val BUFFER_SIZE = 64 * 1024

    /** zip に入れられるファイルの数の上限(JSON を含む) */
    const val MAX_ENTRIES = 50_000
}
