package com.unchunks.echomark.domain.bookmark.model

// ここを変更するときは、com.unchunks.echomark.data.local.entity.BookmarkEntity.kt の変更が不要か確認すること
data class Bookmark(
    val id: Long = 0,
    val type: BookmarkType,
    val content: String? = null,
    val contentUri: String? = null,
    val title: String,
    val summary: String? = null,
    /** 要約をユーザーが手で直したもの。再処理しても要約は置き換えない(要約だけを作り直すときは、確かめてから置き換える) */
    val summaryEdited: Boolean = false,
    val category: String? = null,
    /** カテゴリをユーザーが選んだもの。再処理しても AI はカテゴリを置き換えない */
    val categoryEdited: Boolean = false,
    val createdAt: Long,
    val lastAccessedAt: Long,
    val aiStatus: AiStatus = AiStatus.PENDING,
    val tags: List<String> = emptyList(),
    /** [tags] のうち AI が付けたもの(残りはユーザーが付けたもの)。再処理すると付け直される */
    val aiTags: Set<String> = emptySet(),
    /** リンク先の OG 画像 URL(取得できなければ null) */
    val imageUrl: String? = null,
    /** リンク先のサイト名(og:site_name など。取得できなければ null) */
    val siteName: String? = null,
    val isFavorite: Boolean = false,
    /** アーカイブ済み(通常の一覧には出さない) */
    val isArchived: Boolean = false,
    /** 保存したファイル(画像・PDF・音声など)の、アプリ内の保存先(filesDir からの相対パス)。ファイルでなければ null */
    val filePath: String? = null,
    /** 中身の MIME タイプ(ファイル、またはリンク先が HTML 以外のとき)。不明なら null */
    val mimeType: String? = null,
    /** 元のファイル名(共有・選択されたときの名前)。表示用 */
    val fileName: String? = null,
    /** ファイルのサイズ(バイト)。表示用 */
    val fileSize: Long? = null,
    /** URL の本文(リンク先)を取得できた日時。未取得・取得に失敗したままなら null(再処理で取得し直す) */
    val contentFetchedAt: Long? = null,
    /** 処理の段・日時・失敗の理由([aiStatus] の詳細) */
    val processing: ProcessingInfo = ProcessingInfo(),
    /** 読んだ日時(詳細画面を開いた・読んだことにした)。null なら未読 */
    val readAt: Long? = null,
    /** ページをオフラインで読めるよう保存した MHTML の、アプリ内の保存先(filesDir からの相対パス)。保存していなければ null */
    val snapshotPath: String? = null,
    /** 同じページの PDF の保存先。PDF を作れなかった・保存していなければ null */
    val snapshotPdfPath: String? = null,
    /** オフラインコピーを保存した日時。保存していなければ null */
    val snapshotAt: Long? = null
) {
    /** 読んだか(詳細画面を開いたか、読んだことにしたか) */
    val isRead: Boolean get() = readAt != null

    /** ページのオフラインコピー(MHTML)を保存してある */
    val hasSnapshot: Boolean get() = snapshotPath != null
}
