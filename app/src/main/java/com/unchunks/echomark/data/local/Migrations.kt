package com.unchunks.echomark.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v6 → v7: ブックマークにリンクのメタデータ(OG 画像・サイト名)とお気に入り・アーカイブの列を追加する。
 * 既存行はすべて「お気に入りでない・アーカイブしていない」になる。
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `imageUrl` TEXT")
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `siteName` TEXT")
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `isFavorite` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `isArchived` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v7 → v8: 会話に「このブックマークについて質問」の対象(aboutBookmarkId)を追加する。
 * 既存の会話はすべて通常の会話(null)になる。ブックマーク削除の取り消しで紐付けが戻るよう、外部キーにはしない。
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `conversations` ADD COLUMN `aboutBookmarkId` INTEGER")
    }
}

/**
 * v8 → v9: タグを「ユーザーが付けたもの」と「AI が付けたもの」に分ける。
 * - 紐付け(bookmark_tag_cross_ref)に付けた人(source: 'USER' / 'AI')を追加する
 * - タグ(tags)に、ユーザーのタグか(isUserCreated)を追加する。false のタグはどこにも付かなくなったら自動で消す
 *
 * 既存の紐付け・タグは誰が付けたか分からないため、すべてユーザーのものとして扱う(ユーザーのタグを誤って消さない)。
 * source はエンティティに既定値を宣言していない(常に明示して書き込む)。Room は既定値を宣言していない列の
 * 既定値を検証しないため、既存行を埋めるための DEFAULT を付けて追加しても新規作成の DB と食い違わない。
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `bookmark_tag_cross_ref` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'USER'")
        db.execSQL("ALTER TABLE `tags` ADD COLUMN `isUserCreated` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE `tags` SET `isUserCreated` = 1")
    }
}

/**
 * v9 → v10: ブックマークに、保存したファイル(画像・PDF・音声など)の情報を追加する。
 * 既存のブックマークはファイルを持たない(すべて null)。
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `filePath` TEXT")
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `mimeType` TEXT")
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `fileName` TEXT")
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `fileSize` INTEGER")
    }
}

/**
 * v10 → v11: URL の本文を取得できた日時(contentFetchedAt)を追加。
 * これまでは本文があれば取得済みとみなしていたため、本文のある URL は保存日時で取得済みにする
 * (本文の無いものは未取得のまま。再処理で取得し直す)。
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `contentFetchedAt` INTEGER")
        db.execSQL(
            "UPDATE `bookmarks` SET `contentFetchedAt` = `createdAt` " +
                "WHERE `type` = 'URL' AND `content` IS NOT NULL AND TRIM(`content`) != ''"
        )
    }
}

/** アプリで使う正式なマイグレーション一覧。DatabaseModule とマイグレーションテストで共有する。 */
val ALL_MIGRATIONS: Array<Migration> =
    arrayOf(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)

/** 正式なマイグレーションを用意していない古いバージョン(開発初期)。ここからの更新だけはデータを作り直す。 */
val DESTRUCTIVE_MIGRATION_FROM_VERSIONS: IntArray = intArrayOf(1, 2, 3, 4, 5)
