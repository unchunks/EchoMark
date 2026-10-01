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

/** アプリで使う正式なマイグレーション一覧。DatabaseModule とマイグレーションテストで共有する。 */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_6_7, MIGRATION_7_8)

/** 正式なマイグレーションを用意していない古いバージョン(開発初期)。ここからの更新だけはデータを作り直す。 */
val DESTRUCTIVE_MIGRATION_FROM_VERSIONS: IntArray = intArrayOf(1, 2, 3, 4, 5)
