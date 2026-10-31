package com.unchunks.echomark.domain.capture

/**
 * アプリの中で撮った写真・動画・録音の種類。
 * @property label 保存したときの名前(タイトルの元になる)の頭に付ける言葉
 * @property extension 一時ファイルの拡張子
 * @property mimeType 中身の MIME タイプ
 */
enum class CaptureKind(val label: String, val extension: String, val mimeType: String) {
    PHOTO("写真", "jpg", "image/jpeg"),
    VIDEO("動画", "mp4", "video/mp4"),
    VOICE_MEMO("音声メモ", "m4a", "audio/mp4")
}

/**
 * 撮った・録ったものを書き込む一時ファイル。
 * @property uri ほかのアプリ(カメラ)に書き込ませ、保存のときに読む content:// の URI
 * @property path アプリ自身が書き込むときのファイルの場所
 */
data class CaptureTarget(val uri: String, val mimeType: String, val path: String)

/** 撮った・録ったものの一時ファイル。保存するとアプリ内に取り込まれ、一時ファイルは一定時間後に掃除される */
interface CaptureFiles {
    /** [kind] の空の一時ファイルを作る。作れなければ null */
    fun create(kind: CaptureKind): CaptureTarget?

    /** [uri] の一時ファイルに中身がある(撮影が取り消されていない)か */
    fun hasContent(uri: String): Boolean

    /** [uris] の一時ファイルを消す。一時ファイルでない URI は無視する */
    fun discard(uris: Collection<String>)

    /** 作ってから [olderThanMillis] ミリ秒より長く経った一時ファイルを消す */
    fun purgeStale(olderThanMillis: Long)
}

/** 録音の結果 */
sealed interface VoiceMemoStop {
    /** 録音できた */
    data object Saved : VoiceMemoStop

    /** 短すぎる・書けないなどで録音が残らなかった */
    data object Failed : VoiceMemoStop
}

/** マイクで音声メモを録音する(端末の中だけで動き、通信しない)。マイクの権限は呼び出し側で確かめる */
interface VoiceMemoRecorder {
    /**
     * [target] に録音を始める。マイクを使えなければ false。
     * 録音中にほかのアプリがマイクを使うなどして止まったら [onError] が呼ばれる。
     */
    fun start(target: CaptureTarget, onError: () -> Unit): Boolean

    /** 録音を始めてからの時間(ミリ秒)。録音していなければ 0 */
    fun elapsedMillis(): Long

    /** いまの音の大きさ(0〜1)。録音していなければ 0 */
    fun level(): Float

    /** 録音を終えてファイルを仕上げる */
    fun stop(): VoiceMemoStop

    /** 録音を捨てる */
    fun cancel()
}
