package com.unchunks.echomark.domain.importer

/**
 * 他のサービス(Pocket・Raindrop.io・Instapaper・Pinboard・ブラウザのブックマークなど)の書き出しファイルから読み取った、
 * 取り込む1件のリンク。ファイルから読むだけで、アカウントやログインは扱わない。
 *
 * @property url http / https の URL
 * @property title 元のサービスでのタイトル(無ければ null。保存後にページのタイトルを取得して置き換える)
 * @property note 元のサービスでのメモ・抜粋(無ければ null)
 * @property tags 元のサービスで付いていたタグ
 * @property folder 元のサービスでのフォルダ・コレクション名(階層のときは一番内側)。無ければ null
 * @property savedAt 元のサービスで保存した日時(エポックミリ秒)。分からなければ null
 * @property favorite 元のサービスでお気に入り(スター)だった
 * @property archived 元のサービスでアーカイブ済み(読了)だった
 */
data class ImportedLink(
    val url: String,
    val title: String? = null,
    val note: String? = null,
    val tags: List<String> = emptyList(),
    val folder: String? = null,
    val savedAt: Long? = null,
    val favorite: Boolean = false,
    val archived: Boolean = false
)

/** 読み取ったファイルの形式(画面での表示用) */
enum class ImportFormat(val label: String) {
    /** ブラウザ・Pocket・Raindrop.io・Pinboard などの HTML(Netscape 形式)のブックマーク */
    HTML("ブックマークの HTML"),

    /** Raindrop.io・Pocket・Instapaper などの CSV */
    CSV("CSV"),

    /** Pinboard などの JSON */
    JSON("JSON")
}

/**
 * ファイルを読み取った結果。
 * @property links 取り込めるリンク(同じ URL は1件にまとめ済み)
 * @property invalidCount URL が無い・http / https でないなどで読み飛ばした件数
 * @property truncated 件数が多すぎて、先頭から [MAX_IMPORT_LINKS] 件までにした
 */
data class ImportParseResult(
    val format: ImportFormat,
    val links: List<ImportedLink>,
    val invalidCount: Int = 0,
    val truncated: Boolean = false
) {
    /** フォルダ名を持つリンクがある(「フォルダ名をタグにする」を選べる) */
    val hasFolders: Boolean get() = links.any { it.folder != null }

    /** アーカイブ済み・お気に入りがある */
    val archivedCount: Int get() = links.count { it.archived }
}

/** 取り込みの結果 */
data class ImportResult(
    /** 新しく保存した件数 */
    val added: Int,
    /** 同じ URL が保存済みで、読み飛ばした件数 */
    val duplicates: Int,
    /** 保存に失敗した件数 */
    val failed: Int = 0,
    /** ページの取得・要約を順番に始めた(夜間にまとめて処理する設定などで始めなかったときは false) */
    val processingStarted: Boolean = false
)

/** 1回に取り込むリンクの上限(これより多いファイルは、先頭からこの件数まで) */
const val MAX_IMPORT_LINKS = 10_000

/** 読み込むファイルの大きさの上限(バイト) */
const val MAX_IMPORT_BYTES: Long = 30L * 1024 * 1024
