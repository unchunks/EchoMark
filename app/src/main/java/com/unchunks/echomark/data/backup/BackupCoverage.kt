package com.unchunks.echomark.data.backup

/**
 * バックアップに何を含め、何を含めないかの一覧(とその理由)。DB のすべてのテーブル・列と、DataStore のすべてのキーを載せる。
 *
 * `BackupCoverageTest` が、DB のスキーマ(`app/schemas/.../<版>.json`)と DataStore のキー(ソース)をこの一覧と比べ、
 * 載っていないテーブル・列・キーがあれば失敗する。テーブル・列・キーを足したら、バックアップ([BackupJson])に足すか、
 * 含めない理由を書いてここに足すこと(手順は `docs/backup.md`)。
 */
object BackupCoverage {

    /** 列の扱い */
    sealed interface Column {
        /** バックアップに入れる。[jsonKey] は JSON での名前(DB の列名と違うときだけ変える) */
        data class Included(val jsonKey: String) : Column

        /** 入れない。[reason] は理由 */
        data class Excluded(val reason: String) : Column
    }

    /** テーブルの扱い */
    sealed interface Table {
        /**
         * バックアップに入れる。[jsonArray] は JSON での配列の名前(`collections.items` のように、ほかの配列の中に入るものもある)。
         * [columns] はすべての列の扱い
         */
        data class Included(val jsonArray: String, val columns: Map<String, Column>) : Table

        /** テーブルごと入れない。[reason] は理由 */
        data class Excluded(val reason: String) : Table
    }

    private const val ID_RENUMBERED = "ID は読み込む端末で振り直す(紐付けは対応表で付け替える)"
    private const val PROCESSING_STATE = "処理の段・日時・失敗の理由は端末の中の一時的な状態(読み込んだものは準備待ちにして再処理する)"
    private const val FEED_CHECK_STATE = "確認の状態は端末の中のもの(読み込んだ端末では最初の確認から始める)"
    private const val LINK_CHECK_STATE = "確認の状態は端末の中のもの(読み込んだ端末で確認し直す)"

    private fun included(vararg columns: String): Map<String, Column> = columns.associateWith { Column.Included(it) }
    private fun excluded(reason: String, vararg columns: String): Map<String, Column> = columns.associateWith { Column.Excluded(reason) }

    val tables: Map<String, Table> = mapOf(
        "bookmarks" to Table.Included(
            "bookmarks",
            included(
                "id", "type", "content", "contentUri", "title", "summary", "category", "createdAt", "lastAccessedAt", "aiStatus",
                "imageUrl", "siteName", "isFavorite", "isArchived", "filePath", "mimeType", "fileName", "fileSize", "contentFetchedAt",
                "summaryEdited", "categoryEdited", "readAt", "snapshotPath", "snapshotPdfPath", "snapshotAt",
                "latitude", "longitude", "placeName", "readingListAt"
            ) + excluded(
                PROCESSING_STATE,
                "processingStage", "queuedAt", "processingStartedAt", "stageStartedAt", "processingFinishedAt", "failureReason"
            )
        ),
        "tags" to Table.Included("tags", included("id", "name", "isUserCreated", "parentId")),
        "bookmark_tag_cross_ref" to Table.Included("bookmarkTags", included("bookmarkId", "tagId", "source")),
        "conversations" to Table.Included(
            "conversations",
            included(
                "id", "title", "isTitleManuallySet", "summary", "createdAt", "updatedAt", "aboutBookmarkId", "summaryCoveredUntil",
                "isPinned", "branchedFromId", "branchedAt"
            )
        ),
        "chat_messages" to Table.Included(
            "messages",
            included("id", "conversationId", "role", "content", "referencedBookmarkIds", "createdAt")
        ),
        "api_usage" to Table.Excluded(
            "クラウド API の使用量の記録は、この端末で使った量の目安。別の端末に持ち込むと、その端末で使った量と重なって数えてしまう"
        ),
        "summary_history" to Table.Included(
            "summaryHistory",
            included("bookmarkId", "summary", "source", "generator", "style", "createdAt") + excluded(ID_RENUMBERED, "id")
        ),
        "tag_aliases" to Table.Included(
            "tagAliases",
            included("alias", "tagId") + excluded("別名から作り直す(TagNames.key)", "aliasKey")
        ),
        "removed_tags" to Table.Included("removedTags", included("bookmarkId", "tagKey", "tagName", "removedAt")),
        "categories" to Table.Included("categories", included("name", "description", "createdAt") + excluded(ID_RENUMBERED, "id")),
        "smart_folders" to Table.Included(
            "smartFolders",
            included("name", "filter", "tagMatch", "categories", "types", "period", "readState", "sortOrder", "createdAt") +
                mapOf("tagIds" to Column.Included("tagNames")) +
                excluded(ID_RENUMBERED, "id")
        ),
        "subscriptions" to Table.Included(
            "subscriptions",
            included("type", "feedUrl", "title", "siteUrl", "createdAt", "enabled") +
                excluded(ID_RENUMBERED, "id") +
                excluded(FEED_CHECK_STATE, "lastCheckedAt", "lastSuccessAt", "etag", "lastModified", "failureCount", "lastError", "seenKeys")
        ),
        "bookmark_notes" to Table.Included(
            "notes",
            included("bookmarkId", "text", "quote", "createdAt", "updatedAt") + excluded(ID_RENUMBERED, "id")
        ),
        "collections" to Table.Included("collections", included("name", "description", "createdAt") + excluded(ID_RENUMBERED, "id")),
        "collection_items" to Table.Included(
            "collections.items",
            included("bookmarkId", "addedAt") + excluded("入れているコレクションの中に入れる(コレクションは名前で見分ける)", "collectionId")
        ),
        "link_checks" to Table.Included(
            "linkCheckMuted",
            // 「確認しない」を選んだブックマークの ID の配列として入れる
            mapOf("bookmarkId" to Column.Included("linkCheckMuted"), "muted" to Column.Included("linkCheckMuted")) +
                excluded(LINK_CHECK_STATE, "checkedAt", "failureCount", "firstFailedAt", "deadAt", "lastReason")
        ),
        "review_cards" to Table.Included(
            "reviewCards",
            included("bookmarkId", "dueAt", "intervalDays", "easePermille", "repetitions", "lapses", "lastReviewedAt", "createdAt")
        )
    )

    /** DataStore のファイルの扱い。null なら中のキーごとに [dataStoreKeys] で決める */
    val dataStores: Map<String, String?> = mapOf(
        "app_settings" to null,
        "api_keys" to "API キーは秘密の情報。ファイルとして持ち出さない(読み込んだ端末で入れ直す)",
        "processing_stats" to "処理の所要時間の記録は、端末の性能で決まる目安(別の端末では当てはまらない)"
    )

    private const val DEVICE_SETTING = "端末の設定(画面・処理・AI の選び方など)。端末ごとに性能・接続・好みが違うため持ち越さない"

    /**
     * app_settings のキーの扱い。null なら [BackupJson] に入れる(利用者が作ったもの)、文字列なら入れない理由。
     * 末尾が `*` のものは、用途・提供元などを後ろに付けたキーの集まり(`llm_backend_summary` など)。
     */
    val dataStoreKeys: Map<String, String?> = mapOf(
        "chat_templates" to null,
        "search_history" to "何を探したかは端末の中だけに残す(持ち出すファイルに入れない)",
        "dismissed_duplicates" to "書き出し元のブックマークの ID を覚えているだけで、読み込んだ端末では ID が変わる",
        "rediscover_notified" to "どのブックマークを通知したかは、この端末の通知の記録",
        "onboarding_completed" to "はじめにの案内を見たかは、この端末のこと",
        "offline_mode" to "オフラインモードは端末の設定(読み込みで勝手にオン・オフを変えない)",
        "theme_mode" to DEVICE_SETTING,
        "dynamic_color" to DEVICE_SETTING,
        "send_files_to_cloud" to DEVICE_SETTING,
        "local_llm_use_gpu" to DEVICE_SETTING,
        "bookmark_sort_order" to DEVICE_SETTING,
        "bookmark_list_display" to DEVICE_SETTING,
        "duplicate_sensitivity" to DEVICE_SETTING,
        "link_check_enabled" to DEVICE_SETTING,
        "places_map_background" to DEVICE_SETTING,
        "reading_font" to DEVICE_SETTING,
        "reading_font_size" to DEVICE_SETTING,
        "reading_theme" to DEVICE_SETTING,
        "rediscover_enabled" to DEVICE_SETTING,
        "rediscover_day_of_week" to DEVICE_SETTING,
        "rediscover_hour" to DEVICE_SETTING,
        "rediscover_minute" to DEVICE_SETTING,
        "summary_format" to DEVICE_SETTING,
        "summary_language" to DEVICE_SETTING,
        "summary_quality_check" to DEVICE_SETTING,
        "processing_*" to DEVICE_SETTING,
        "llm_backend" to DEVICE_SETTING,
        "llm_backend_*" to DEVICE_SETTING,
        "api_provider" to DEVICE_SETTING,
        "api_provider_*" to DEVICE_SETTING,
        "api_model_*" to DEVICE_SETTING,
        "local_model_*" to "端末内モデルは端末ごとに取り込むもの(読み込んだ端末に同じモデルがあるとは限らない)"
    )

    /** 添付ファイル・オフラインコピーの本体の扱い */
    const val FILES = "「ファイルも含めて書き出す」(zip)のときだけ入れる。JSON だけのときは入れず、読み込む端末に無いファイルは外す"

    /** ObjectBox(検索用の埋め込みベクトル)の扱い */
    const val EMBEDDINGS = "タイトル・要約・本文から作り直せる派生データ。読み込んだあとに作り直す"

    /** 端末内モデルの扱い */
    const val MODELS = "大きく、おすすめの一覧から取り込み直せる"
}
