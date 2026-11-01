package com.unchunks.echomark.domain.snapshot

import java.io.File

/**
 * 保存したページのオフラインコピー。
 * @property mhtmlPath MHTML(ページとその画像・CSS を1つにまとめたもの)の、アプリ内の保存先(filesDir からの相対パス)
 * @property pdfPath PDF の保存先。作れなかったときは null
 * @property savedAt 保存した日時
 */
data class StoredSnapshot(val mhtmlPath: String, val pdfPath: String?, val savedAt: Long)

/** ページのオフラインコピーの保存・削除。ファイルはアプリ内の保存先(添付ファイルと同じ場所)に置き、ブックマークに結びつける */
interface PageSnapshotRepository {
    /**
     * 一時ファイルの [mhtml] と [pdf] をアプリ内へ移してブックマーク [bookmarkId] に結びつける。前のコピーがあれば消す。
     * 一時ファイルは移したあとに消す。ブックマークが無い・移せないときは例外(一時ファイルは呼び出し側で消す)。
     */
    suspend fun save(bookmarkId: Long, mhtml: File, pdf: File?): StoredSnapshot

    /** ブックマークのオフラインコピーを消す(ファイルも)。無ければ何もしない。 */
    suspend fun remove(bookmarkId: Long)

    /** 保存先の相対パス [path] のファイル。無い・保存先でないパスなら null。 */
    fun fileOf(path: String?): File?
}
