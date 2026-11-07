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

    /** app_settings のキーの扱い */
    sealed interface Key {
        /** 利用者が作ったもの(チャットのテンプレート)。バックアップに入れ、読み込むときに今のものへ足す */
        data object UserData : Key

        /**
         * 端末の設定。バックアップに入れ、読み込んだあとに利用者が「設定も戻す」を選んだときだけ戻す。
         * [type] は値の型(バックアップの値の型が違えば戻さない)。[restorableValues] があれば、その値のときだけ戻す
         * ([valueReason] はその理由。端末の外にデータを送る値を、受け取ったファイルから勝手に選ばないため)
         */
        data class Setting(
            val type: SettingType,
            val restorableValues: Set<String>? = null,
            val valueReason: String? = null
        ) : Key

        /** 入れない。[reason] は理由 */
        data class Excluded(val reason: String) : Key
    }

    enum class SettingType { BOOLEAN, INT, STRING }

    private const val APP_LOCK = "アプリのロックは端末の設定(端末ごとに生体認証・画面ロックが違う)。バックアップを戻して勝手にオン・オフを変えない"
    private const val AUTO_BACKUP = "自動バックアップの保存先は、この端末のフォルダ(別の端末では開けない)"
    private const val SENDS_DATA =
        "端末の外にデータを送る設定(クラウドへのファイルの送信・地図のタイル・リンクの確認・クラウド AI)。" +
            "書き換えた・人から受け取ったバックアップで、勝手にオンにしない(この端末で自分で選ぶ)"
    private const val LEGACY = "用途を分ける前の古い形式の設定(読むだけで書かない。用途ごとの設定を戻す)"

    private val BOOLEAN = Key.Setting(SettingType.BOOLEAN)
    private val INT = Key.Setting(SettingType.INT)
    private val STRING = Key.Setting(SettingType.STRING)

    /**
     * app_settings のキーの扱い。末尾が `*` のものは、用途・提供元などを後ろに付けたキーの集まり(`llm_backend_summary` など)。
     * 具体的な名前のほうが `*` より先に当たる。
     */
    val dataStoreKeys: Map<String, Key> = mapOf(
        "chat_templates" to Key.UserData,
        // 端末の設定(戻すかは利用者が決める)
        "theme_mode" to STRING,
        "dynamic_color" to BOOLEAN,
        "bookmark_sort_order" to STRING,
        "bookmark_list_display" to STRING,
        "duplicate_sensitivity" to STRING,
        "reading_font" to STRING,
        "reading_font_size" to STRING,
        "reading_theme" to STRING,
        "rediscover_enabled" to BOOLEAN,
        "rediscover_day_of_week" to INT,
        "rediscover_hour" to INT,
        "rediscover_minute" to INT,
        "summary_format" to STRING,
        "summary_language" to STRING,
        "summary_quality_check" to BOOLEAN,
        "processing_charging_only" to BOOLEAN,
        "processing_unmetered_only" to BOOLEAN,
        "processing_nightly_batch" to BOOLEAN,
        "processing_nightly_hour" to INT,
        "processing_max_concurrent" to INT,
        "processing_api_requests_per_minute" to INT,
        // 端末内で動かす(LOCAL)ときだけ戻す。クラウド API を選ぶ値は、受け取ったファイルから勝手に選ばない
        "llm_backend_*" to Key.Setting(SettingType.STRING, restorableValues = setOf("LOCAL"), valueReason = SENDS_DATA),
        "api_provider_*" to STRING,
        "api_model_*" to STRING,
        // 入れないもの
        // 端末の外にデータを送る設定。書き換えた・人から受け取ったファイルで、勝手にオンにしない
        "send_files_to_cloud" to Key.Excluded(SENDS_DATA),
        "places_map_background" to Key.Excluded(SENDS_DATA),
        "link_check_enabled" to Key.Excluded(SENDS_DATA),
        "llm_backend" to Key.Excluded(LEGACY),
        "api_provider" to Key.Excluded(LEGACY),
        "search_history" to Key.Excluded("何を探したかは端末の中だけに残す(持ち出すファイルに入れない)"),
        "dismissed_duplicates" to Key.Excluded("書き出し元のブックマークの ID を覚えているだけで、読み込んだ端末では ID が変わる"),
        "rediscover_notified" to Key.Excluded("どのブックマークを通知したかは、この端末の通知の記録"),
        "onboarding_completed" to Key.Excluded("はじめにの案内を見たかは、この端末のこと"),
        "offline_mode" to Key.Excluded("オフラインモードは端末の設定(バックアップを戻して勝手に通信を始めない・止めない)"),
        "app_lock_enabled" to Key.Excluded(APP_LOCK),
        "app_lock_delay" to Key.Excluded(APP_LOCK),
        "app_lock_hide_content" to Key.Excluded(APP_LOCK),
        "local_llm_use_gpu" to Key.Excluded("GPU で動くかは端末ごとに違う(落ちる端末もあるため、端末ごとに試して決める)"),
        "local_model_*" to Key.Excluded("端末内モデルは端末ごとに取り込むもの(読み込んだ端末に同じモデルがあるとは限らない)"),
        "auto_backup_*" to Key.Excluded(AUTO_BACKUP)
    )

    /** [name] のキーの扱い(具体的な名前を先に見て、無ければ `*` の集まりを見る)。一覧に無ければ null */
    fun keyOf(name: String): Key? =
        dataStoreKeys[name] ?: dataStoreKeys.entries.firstOrNull { (key, _) -> key.endsWith("*") && name.startsWith(key.removeSuffix("*")) }?.value

    /** バックアップに入れて、利用者が選んだら戻す設定の型。戻さないキーなら null */
    fun settingTypeOf(name: String): SettingType? = (keyOf(name) as? Key.Setting)?.type

    /** [name] の設定を [value] で戻してよいか(型が合い、値が決めた範囲にあるとき) */
    fun isRestorable(name: String, value: Any): Boolean {
        val setting = keyOf(name) as? Key.Setting ?: return false
        val typed = when (setting.type) {
            SettingType.BOOLEAN -> value is Boolean
            SettingType.INT -> value is Int
            SettingType.STRING -> value is String
        }
        return typed && (setting.restorableValues == null || value.toString() in setting.restorableValues)
    }

    /** 添付ファイル・オフラインコピーの本体の扱い */
    const val FILES = "「ファイルも含めて書き出す」(zip)のときだけ入れる。JSON だけのときは入れず、読み込む端末に無いファイルは外す"

    /** ObjectBox(検索用の埋め込みベクトル)の扱い */
    const val EMBEDDINGS = "タイトル・要約・本文から作り直せる派生データ。読み込んだあとに作り直す"

    /** 端末内モデルの扱い */
    const val MODELS = "端末内モデルと、その速さの記録(ベンチマーク)は端末ごとのもの。大きく、おすすめの一覧から取り込み直せる"
}
