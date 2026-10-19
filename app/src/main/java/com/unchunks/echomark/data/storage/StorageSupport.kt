package com.unchunks.echomark.data.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import java.io.File

/*
 * 端末のファイルと保存先についての問い合わせ。添付ファイル・モデルの取り込みと、共有・選択されたファイルの確認で共通に使う。
 */

/** content:// の URI が指すファイルの表示名とサイズ(バイト)。提供元が教えなければ null。 */
internal data class OpenableInfo(val displayName: String?, val sizeBytes: Long?)

/** [uri] の表示名とサイズを提供元に問い合わせる。問い合わせの失敗(権限の失効・提供元の不具合など)は呼び出し側へ投げる。 */
internal fun ContentResolver.queryOpenableInfo(uri: Uri): OpenableInfo {
    var name: String? = null
    var size: Long? = null
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
        }
    }
    return OpenableInfo(name, size)
}

/** [dir] に確保できる容量。消去可能なキャッシュ分も含めて見積もる。 */
internal fun Context.allocatableBytes(dir: File): Long = try {
    val storageManager = getSystemService(StorageManager::class.java)
    storageManager.getAllocatableBytes(storageManager.getUuidForPath(dir))
} catch (e: Exception) {
    // 保存先の情報を取れない場合(IOException など)は、通常の空き容量で見積もる
    dir.usableSpace
}
