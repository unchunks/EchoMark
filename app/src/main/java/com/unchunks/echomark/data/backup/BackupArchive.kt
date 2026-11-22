package com.unchunks.echomark.data.backup

import com.unchunks.components.archive.ZipArchive
import com.unchunks.components.archive.ZipArchiveException
import com.unchunks.components.archive.ZipArchiveWriter
import com.unchunks.echomark.data.attachment.isValidAttachmentPath
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 「ファイルも含めて書き出す」バックアップ(zip)の形式。Android に依存しない純粋な処理で、単体テストできる。
 * zip の読み書きの仕組み(上限つきの展開・壊れた zip の判定)は部品の archive([ZipArchive])にあり、ここでは中身の決まりだけを持つ。
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

    /** 先頭の数バイトが zip の印か(JSON のバックアップと見分ける) */
    fun isZip(header: ByteArray): Boolean = ZipArchive.isZip(header)

    /**
     * [data] を JSON として先頭に入れ、続けて [files](添付ファイルの相対パス → ファイル)を入れる。[output] は閉じない。
     * 写真・動画などはもとから圧縮されているため、ファイルは圧縮せずに入れる(書き出しを速くする)。
     */
    fun write(data: BackupData, files: Map<String, File>, output: OutputStream) {
        val zip = ZipArchiveWriter(output)
        zip.putText(JSON_ENTRY) { writer -> BackupJson.write(data, writer) }
        for ((path, file) in files.toSortedMap()) {
            require(isValidAttachmentPath(path)) { "unsafe path: $path" }
            zip.putStoredFile(path, file)
        }
        zip.finish()
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
        try {
            ZipArchive.forEachEntry(input, maxEntries) { entry, content ->
                val name = entry.name
                when {
                    name == JSON_ENTRY -> {
                        if (json != null) throw BackupFormatException("バックアップの中身が2つあります")
                        json = readBackupText(content, maxJsonBytes)
                    }
                    isValidAttachmentPath(name) && !entry.isDirectory -> {
                        if (name in files) throw BackupFormatException("同じ名前のファイルが2つあります")
                        val dest = File(stagingDir, name.substringAfter('/'))
                        total += ZipArchive.copyCapped(content, dest, maxFileBytes, maxTotalBytes - total)
                        files[name] = dest
                    }
                    else -> throw BackupFormatException("EchoMark のバックアップに無いファイルが入っています")
                }
            }
        } catch (e: ZipArchiveException) {
            // 壊れた zip・ファイルの数や大きさの上限。メッセージはそのまま画面に出す
            throw BackupFormatException(e.message ?: ZipArchive.BROKEN_ZIP_MESSAGE, e)
        }
        return Staged(json ?: throw BackupFormatException("zip の中にバックアップ(backup.json)がありません"), files)
    }

    /** zip に入れられるファイルの数の上限(JSON を含む) */
    const val MAX_ENTRIES = 50_000
}
