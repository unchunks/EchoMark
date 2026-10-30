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

/**
 * v11 → v12: 処理待ちと処理中を分け、処理の段・日時・失敗の理由(ProcessingInfo)を持てるようにする。
 * - 処理の段(processingStage)、処理を頼んだ・始めた・今の段を始めた・終えた日時、失敗の理由(failureReason)を追加する
 * - 処理中(PROCESSING)の行は処理待ち(PENDING)にする。アプリの更新でプロセスは止まっているため、実行中のワークは無い
 *   (残っているワークは再実行されたときに段を書き、ワークの無いものは起動時に積み直す)
 * - 処理待ちの行は、頼んだ日時が分からないため保存日時を入れる(順番待ちの並べ方に使う)。
 *   それ以外の日時・失敗の理由は分からないため空のままにする
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `processingStage` TEXT")
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `queuedAt` INTEGER")
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `processingStartedAt` INTEGER")
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `stageStartedAt` INTEGER")
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `processingFinishedAt` INTEGER")
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `failureReason` TEXT")
        db.execSQL("UPDATE `bookmarks` SET `aiStatus` = 'PENDING' WHERE `aiStatus` = 'PROCESSING'")
        db.execSQL("UPDATE `bookmarks` SET `queuedAt` = `createdAt` WHERE `aiStatus` = 'PENDING'")
    }
}

/**
 * v12 → v13: 会話の要約に含めた最後の発言の日時(summaryCoveredUntil)を追加する。
 * 既存の会話に要約はまだ無い(summary はこれまで書いていない)ため空のままにし、長い会話は次の発言の後に要約を作り始める。
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `conversations` ADD COLUMN `summaryCoveredUntil` INTEGER")
    }
}

/**
 * アプリで使う正式なマイグレーション一覧。DatabaseModule とマイグレーションテストで共有する。
 * [OLDEST_MIGRATABLE_VERSION] から [AppDatabase.VERSION] までの各版から、ここにあるものだけで現在の版へ上がれること
 * (MigrationPathTest で確認している)。
 */
val ALL_MIGRATIONS: Array<Migration> =
    arrayOf(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)

/** 正式なマイグレーションでデータを保ったまま更新できる最も古い版。これより上げてはいけない(公開版の利用者のデータが消える)。 */
const val OLDEST_MIGRATABLE_VERSION = 6

/** 公開したアプリ(v1.0 プレリリース、2026-10-11)の DB の版。利用者の端末にはこの版以降の DB しか無い。 */
const val FIRST_RELEASED_VERSION = 11

/**
 * 正式なマイグレーションを用意していない古いバージョン(開発初期)。ここからの更新だけはデータを作り直す。
 * どれも公開前([FIRST_RELEASED_VERSION] より前)の開発用ビルドにしか無く、v5 はスキーマも残っていない
 * (同じ日に v6 へ上げたため書き出していない)。新しい版をここに足してはいけない。
 */
val DESTRUCTIVE_MIGRATION_FROM_VERSIONS: IntArray = (1 until OLDEST_MIGRATABLE_VERSION).toList().toIntArray()
