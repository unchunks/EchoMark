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
 * v12 → v13: クラウド API の使用量(トークン数)の記録テーブル(api_usage)を追加(#69)。
 * 新しいテーブルを足すだけで、既存のテーブル・行には触れない(記録は空から始まる)。
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // スキーマ JSON(13.json)の createSql と同じ
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `api_usage` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `provider` TEXT NOT NULL, `model` TEXT NOT NULL, " +
                "`task` TEXT NOT NULL, `inputTokens` INTEGER NOT NULL, `outputTokens` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_api_usage_createdAt` ON `api_usage` (`createdAt`)")
    }
}

/**
 * v13 → v14: 会話の要約に含めた最後の発言の日時(summaryCoveredUntil)を追加する。
 * 既存の会話に要約はまだ無い(summary はこれまで書いていない)ため空のままにし、長い会話は次の発言の後に要約を作り始める。
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `conversations` ADD COLUMN `summaryCoveredUntil` INTEGER")
    }
}

/**
 * v14 → v15: 要約の履歴(summary_history、#21)と、要約を手で直した印(bookmarks.summaryEdited、#19)を追加。
 * - 履歴: 既にある要約は、AI が作った版として1版ずつ入れる(作った日時は処理を終えた日時、無ければ保存日時)。
 *   手で直す機能が無かったため、既存の要約はすべて AI が作ったもの
 * - 印: 既存の行はすべて 0(手で直していない)
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `summaryEdited` INTEGER NOT NULL DEFAULT 0")
        // スキーマ JSON(15.json)の createSql と同じ
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `summary_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`bookmarkId` INTEGER NOT NULL, `summary` TEXT NOT NULL, `source` TEXT NOT NULL, `generator` TEXT, " +
                "`style` TEXT, `createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`bookmarkId`) REFERENCES `bookmarks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_summary_history_bookmarkId` ON `summary_history` (`bookmarkId`)")
        db.execSQL(
            "INSERT INTO `summary_history` (`bookmarkId`, `summary`, `source`, `generator`, `style`, `createdAt`) " +
                "SELECT `id`, `summary`, 'AI', NULL, NULL, COALESCE(`processingFinishedAt`, `createdAt`) " +
                "FROM `bookmarks` WHERE `summary` IS NOT NULL AND TRIM(`summary`) != ''"
        )
    }
}

/**
 * v15 → v16: タグの階層(#29)。tags に親のタグ(parentId)とそのインデックスを足す。
 * 既存のタグはすべて最上位(parentId = NULL)のままで、値は変わらない。親を消すと子は最上位に戻る(ON DELETE SET NULL)。
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // スキーマ JSON(16.json)の createSql と同じ外部キー
        db.execSQL(
            "ALTER TABLE `tags` ADD COLUMN `parentId` INTEGER " +
                "REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tags_parentId` ON `tags` (`parentId`)")
    }
}

/**
 * v16 → v17: タグの別名(tag_aliases、#26)と、ユーザーが外したタグの記録(removed_tags、#31)を追加する。
 * どちらも空で始める(既存のタグ・ブックマークの行は変わらない)。
 */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // スキーマ JSON(17.json)の createSql と同じ
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tag_aliases` (`aliasKey` TEXT NOT NULL, `alias` TEXT NOT NULL, `tagId` INTEGER NOT NULL, " +
                "PRIMARY KEY(`aliasKey`), FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tag_aliases_tagId` ON `tag_aliases` (`tagId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `removed_tags` (`bookmarkId` INTEGER NOT NULL, `tagKey` TEXT NOT NULL, `tagName` TEXT NOT NULL, " +
                "`removedAt` INTEGER NOT NULL, PRIMARY KEY(`bookmarkId`, `tagKey`), " +
                "FOREIGN KEY(`bookmarkId`) REFERENCES `bookmarks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
    }
}

/**
 * v17 → v18: ユーザーが作るカテゴリ(categories、#33)と、カテゴリを自分で選んだ印(bookmarks.categoryEdited)を追加する。
 * - categories は空で始める(標準のカテゴリはコードで持つ)
 * - 印: 既存の行はすべて 0(カテゴリは今まで AI が付けたものだけ)。bookmarks.category の値は変わらない
 */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `categoryEdited` INTEGER NOT NULL DEFAULT 0")
        // スキーマ JSON(18.json)の createSql と同じ
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`description` TEXT NOT NULL DEFAULT '', `createdAt` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_name` ON `categories` (`name`)")
    }
}

/**
 * v18 → v19: 会話の固定(conversations.isPinned、#48)と、分けた元の会話(branchedFromId・branchedAt、#44)を追加する。
 * - 既存の行: isPinned は 0(固定していない)、branchedFromId・branchedAt は NULL(分けていない)。ほかの列は変わらない
 */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `conversations` ADD COLUMN `isPinned` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `conversations` ADD COLUMN `branchedFromId` INTEGER")
        db.execSQL("ALTER TABLE `conversations` ADD COLUMN `branchedAt` INTEGER")
    }
}

/**
 * v19 → v20: 読んだ日時(bookmarks.readAt、#60)を追加する。null は未読。
 * - 既存の行: 保存したあとに開いたことがある(lastAccessedAt が createdAt より後)ものは、最後に開いた日時で既読にする。
 *   一度も開いていないものは未読(NULL)のまま。ほかの列は変わらない
 */
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `readAt` INTEGER")
        db.execSQL("UPDATE `bookmarks` SET `readAt` = `lastAccessedAt` WHERE `lastAccessedAt` > `createdAt`")
    }
}

/**
 * v20 → v21: ページのオフラインコピー(#80)の列を足す。
 * - bookmarks.snapshotPath: MHTML の保存先(filesDir からの相対パス)
 * - bookmarks.snapshotPdfPath: PDF の保存先
 * - bookmarks.snapshotAt: 保存した日時
 * - 既存の行: どれも NULL(オフラインコピーなし)。ほかの列は変わらない
 */
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `snapshotPath` TEXT")
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `snapshotPdfPath` TEXT")
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `snapshotAt` INTEGER")
    }
}

/**
 * v21 → v22: スマートフォルダ(smart_folders、#34)のテーブルを足す。空で始める。既存のテーブルは変わらない。
 */
val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // スキーマ JSON(22.json)の createSql と同じ
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `smart_folders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`filter` TEXT NOT NULL, `tagIds` TEXT NOT NULL, `tagMatch` TEXT NOT NULL, `categories` TEXT NOT NULL, " +
                "`types` TEXT NOT NULL, `period` TEXT, `readState` TEXT, `sortOrder` TEXT, `createdAt` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_smart_folders_name` ON `smart_folders` (`name`)")
    }
}

/**
 * v22 → v23: 購読するフィード(subscriptions、#78)のテーブルを足す。空で始める。既存のテーブルは変わらない。
 */
val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // スキーマ JSON(23.json)の createSql と同じ
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `subscriptions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, " +
                "`feedUrl` TEXT NOT NULL, `title` TEXT NOT NULL, `siteUrl` TEXT, `createdAt` INTEGER NOT NULL, " +
                "`enabled` INTEGER NOT NULL DEFAULT 1, `lastCheckedAt` INTEGER, `lastSuccessAt` INTEGER, `etag` TEXT, " +
                "`lastModified` TEXT, `failureCount` INTEGER NOT NULL DEFAULT 0, `lastError` TEXT, `seenKeys` TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_subscriptions_feedUrl` ON `subscriptions` (`feedUrl`)")
    }
}

/**
 * アプリで使う正式なマイグレーション一覧。DatabaseModule とマイグレーションテストで共有する。
 * [OLDEST_MIGRATABLE_VERSION] から [AppDatabase.VERSION] までの各版から、ここにあるものだけで現在の版へ上がれること
 * (MigrationPathTest で確認している)。
 */
val ALL_MIGRATIONS: Array<Migration> =
    arrayOf(
        MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
        MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19,
        MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23
    )

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
