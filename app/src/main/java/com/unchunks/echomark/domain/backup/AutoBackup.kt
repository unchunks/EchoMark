package com.unchunks.echomark.domain.backup

/**
 * 自動バックアップ(毎晩、利用者が選んだフォルダに書き出す)。
 * @property folderUri 保存先のフォルダ(SAF のツリーの URI。読み書きの許可を持ち続ける)。選んでいなければ null
 * @property folderLabel 画面に出すフォルダの名前
 * @property keep 残すバックアップの数。超えたら古いものから消す(自動バックアップで作ったものだけ)
 * @property includeFiles 添付ファイル・オフラインコピーも入れた zip にする(大きくなる)
 * @property lastSuccessAt 最後に書き出せた日時
 * @property lastError 最後に書き出せなかった理由(書き出せたら null に戻す)
 */
data class AutoBackupSettings(
    val enabled: Boolean = false,
    val folderUri: String? = null,
    val folderLabel: String? = null,
    val keep: Int = DEFAULT_KEEP,
    val includeFiles: Boolean = false,
    val lastSuccessAt: Long? = null,
    val lastError: String? = null
) {
    companion object {
        const val DEFAULT_KEEP = 5

        /** 設定画面で選べる、残す数 */
        val KEEP_CHOICES = listOf(3, 5, 10, 20)

        /** 自動バックアップで作るファイルの名前の始まり(消すのは、この名前のものだけ) */
        const val FILE_PREFIX = "echomark-auto-backup-"
    }
}
