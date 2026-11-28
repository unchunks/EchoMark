package com.unchunks.echomark.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * v32 で追加。動画のブックマークを、通信できなくても見られるように保存した記録(ブックマーク1件に1行)。
 *
 * 動画のファイルそのものは、ダウンロードが許されているもの(動画のファイルへの直接のリンク・ダウンロードを認めた
 * PeerTube の動画など)だけを保存する。YouTube などの動画は保存せず、字幕・要約(ブックマークの本文・要約に入る)と
 * サムネイルだけを保存する(理由は `docs/video-offline.md`)。
 *
 * ブックマークの削除は「元に戻す」で取り消せるため、外部キーにはしない(連鎖して消すと、戻したときに保存が消える)。
 * ブックマークが無くなった行は、起動時の掃除(UnusedAttachmentCleaner)で消す。ID は使い回されない(AUTOINCREMENT)。
 * バックアップには入れない(端末ごとの保存。サムネイル・動画は取り直せ、動画は大きい)。
 *
 * @property thumbnailPath サムネイルの画像の保存先(filesDir からの相対パス。添付ファイルと同じ置き場所)。無ければ null
 * @property savedAt 保存した(最後に保存し直した)日時
 * @property videoPath 動画のファイルの保存先(filesDir からの相対パス)。保存していなければ null
 * @property videoMimeType 動画のファイルの MIME タイプ
 * @property videoSize 動画のファイルの大きさ(バイト)
 * @property videoSourceUrl 動画のファイルを取った URL(保存し直す・出どころを見せるため)
 * @property videoLabel 動画の画質などの表示用の名前(例: 720p)。無ければ null
 * @property videoSavedAt 動画のファイルを保存した日時
 */
@Entity(tableName = "offline_videos")
data class OfflineVideoEntity(
    @PrimaryKey
    val bookmarkId: Long,
    val thumbnailPath: String? = null,
    val savedAt: Long,
    val videoPath: String? = null,
    val videoMimeType: String? = null,
    val videoSize: Long? = null,
    val videoSourceUrl: String? = null,
    val videoLabel: String? = null,
    val videoSavedAt: Long? = null
)
