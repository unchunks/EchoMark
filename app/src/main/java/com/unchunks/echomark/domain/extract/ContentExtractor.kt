package com.unchunks.echomark.domain.extract

import com.unchunks.echomark.domain.bookmark.model.ContentKind
import java.io.File

/**
 * 保存したファイルから、要約・検索・チャットに使うテキストを取り出す。種類ごとの実装を束ねる。
 * 画像は OCR とラベル、PDF はテキスト抽出(スキャンなら OCR)、音声・動画は文字起こし。
 */
interface ContentExtractor {

    /**
     * 取り出したテキストを返す。未対応の形式・取り出せる文字が無い・失敗のときは null(例外は投げない)。
     * コルーチンのキャンセルだけは伝える。
     *
     * @param file 端末内のファイル
     * @param mimeType ファイルの MIME タイプ(不明なら null。[kind] と拡張子から判断する)
     * @param kind 中身の種類
     */
    suspend fun extract(file: File, mimeType: String?, kind: ContentKind): ExtractedContent?

    /**
     * 処理に時間がかかりそうか(長い音声・動画の文字起こしなど)。
     * true ならワーカーはフォアグラウンド(通知を出して)で実行し、途中で止められにくくする。
     */
    suspend fun isLongRunning(file: File, mimeType: String?, kind: ContentKind): Boolean
}
