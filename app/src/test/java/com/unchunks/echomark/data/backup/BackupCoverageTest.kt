package com.unchunks.echomark.data.backup

import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.local.entity.BookmarkNoteEntity
import com.unchunks.echomark.data.local.entity.BookmarkTagCrossRef
import com.unchunks.echomark.data.local.entity.CategoryEntity
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.local.entity.ConversationEntity
import com.unchunks.echomark.data.local.entity.RemovedTagEntity
import com.unchunks.echomark.data.local.entity.ReviewCardEntity
import com.unchunks.echomark.data.local.entity.SubscriptionEntity
import com.unchunks.echomark.data.local.entity.SummaryHistoryEntity
import com.unchunks.echomark.data.local.entity.TagAliasEntity
import com.unchunks.echomark.data.local.entity.TagEntity
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.model.ChatTemplate
import com.unchunks.echomark.domain.model.TagNames
import com.unchunks.echomark.domain.model.TagSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * バックアップが DB のすべてのテーブル・列と、DataStore のすべてのキーを扱っているか([BackupCoverage])。
 * テーブル・列・キーを足したのにバックアップに足していない(含めない理由も書いていない)と失敗する。
 */
class BackupCoverageTest {

    /** 今の版のスキーマ(ビルドが書き出す。コミットしてある) */
    private val schema: JSONObject by lazy {
        val file = File("schemas/com.unchunks.echomark.data.local.AppDatabase/${AppDatabase.VERSION}.json")
        assertTrue("スキーマが見つからない: ${file.absolutePath}", file.isFile)
        JSONObject(file.readText()).getJSONObject("database")
    }

    /** テーブル名 → 列名 */
    private val schemaTables: Map<String, List<String>> by lazy {
        val entities = schema.getJSONArray("entities")
        (0 until entities.length()).associate { index ->
            val entity = entities.getJSONObject(index)
            val fields = entity.getJSONArray("fields")
            entity.getString("tableName") to (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
        }
    }

    @Test
    fun DBのすべてのテーブルと列の扱いが決めてある() {
        val problems = mutableListOf<String>()
        for ((table, columns) in schemaTables) {
            when (val coverage = BackupCoverage.tables[table]) {
                null -> problems += "テーブル $table の扱いが BackupCoverage に無い(バックアップに足すか、含めない理由を書く)"
                is BackupCoverage.Table.Excluded -> if (coverage.reason.isBlank()) problems += "テーブル $table の含めない理由が空"
                is BackupCoverage.Table.Included -> {
                    columns.filter { it !in coverage.columns }.forEach { problems += "列 $table.$it の扱いが BackupCoverage に無い" }
                    coverage.columns.keys.filter { it !in columns }.forEach { problems += "列 $table.$it はもう無い(BackupCoverage から外す)" }
                    coverage.columns.filterValues { it is BackupCoverage.Column.Excluded && it.reason.isBlank() }
                        .keys.forEach { problems += "列 $table.$it の含めない理由が空" }
                }
            }
        }
        BackupCoverage.tables.keys.filter { it !in schemaTables }.forEach { problems += "テーブル $it はもう無い(BackupCoverage から外す)" }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    /** すべての列に値を入れた(省略される値の無い)バックアップ */
    private val full = BackupData(
        exportedAt = 1_700_000_000_000L,
        bookmarks = listOf(
            BookmarkEntity(
                id = 1, type = BookmarkType.IMAGE, content = "本文", contentUri = "https://example.com/a", title = "記事",
                summary = "要約", category = "技術", summaryEdited = true, categoryEdited = true, createdAt = 100, lastAccessedAt = 200,
                aiStatus = AiStatus.DONE, imageUrl = "https://example.com/og.png", siteName = "Example", isFavorite = true,
                isArchived = true, filePath = "attachments/a1.jpg", mimeType = "image/jpeg", fileName = "a.jpg", fileSize = 10,
                contentFetchedAt = 150, readAt = 210, snapshotPath = "attachments/s1.mhtml", snapshotPdfPath = "attachments/s1.pdf",
                snapshotAt = 220, latitude = 35.0, longitude = 139.0, placeName = "東京", readingListAt = 230
            )
        ),
        tags = listOf(TagEntity(10, "kotlin", isUserCreated = true, parentId = 11), TagEntity(11, "言語", isUserCreated = true)),
        bookmarkTags = listOf(BookmarkTagCrossRef(1, 10, TagSource.USER)),
        conversations = listOf(
            ConversationEntity(
                id = 5, title = "質問", isTitleManuallySet = true, summary = "記憶", createdAt = 400, updatedAt = 500,
                aboutBookmarkId = 1, summaryCoveredUntil = 450, isPinned = true, branchedFromId = 4, branchedAt = 401
            )
        ),
        messages = listOf(
            ChatMessageEntity(id = 7, conversationId = 5, role = ChatRole.USER, content = "A?", referencedBookmarkIds = "1", createdAt = 402)
        ),
        tagAliases = listOf(TagAliasEntity(aliasKey = TagNames.key("Kotlin言語"), alias = "Kotlin言語", tagId = 10)),
        removedTags = listOf(RemovedTagEntity(bookmarkId = 1, tagKey = TagNames.key("java"), tagName = "java", removedAt = 600)),
        categories = listOf(CategoryEntity(name = "技術", description = "説明", createdAt = 50)),
        smartFolders = listOf(
            SmartFolderBackup(
                name = "未読", filter = "FAVORITES", tagNames = listOf("kotlin"), tagMatch = "ANY", categories = listOf("技術"),
                types = listOf("URL"), period = "PAST_MONTH", readState = "UNREAD", sortOrder = "OLDEST", createdAt = 60
            )
        ),
        subscriptions = listOf(
            SubscriptionEntity(type = "RSS", feedUrl = "https://example.com/feed", title = "ブログ", siteUrl = "https://example.com/", createdAt = 70)
        ),
        notes = listOf(BookmarkNoteEntity(bookmarkId = 1, text = "ノート", quote = "引用", createdAt = 80, updatedAt = 90)),
        collections = listOf(CollectionBackup(name = "あとで", description = "説明", createdAt = 30, items = listOf(CollectionItemBackup(1, 31)))),
        linkCheckMutedBookmarkIds = listOf(1),
        reviewCards = listOf(
            ReviewCardEntity(
                bookmarkId = 1, dueAt = 700, intervalDays = 3, easePermille = 2_500, repetitions = 2, lapses = 1,
                lastReviewedAt = 650, createdAt = 640
            )
        ),
        summaryHistory = listOf(
            SummaryHistoryEntity(bookmarkId = 1, summary = "前の要約", source = "AI", generator = "端末内 AI", style = "日本語・文章", createdAt = 120)
        ),
        chatTemplates = listOf(ChatTemplate(id = "t1", title = "要点", text = "要点をまとめて")),
        settings = mapOf("theme_mode" to "DARK", "dynamic_color" to true, "processing_nightly_hour" to 3, "llm_backend_summary" to "LOCAL")
    )

    @Test
    fun 含めると決めた列はすべてJSONに書き出され_読み込むと元に戻る() {
        val text = BackupJson.encode(full)
        val root = JSONObject(text)
        val problems = mutableListOf<String>()
        for ((table, coverage) in BackupCoverage.tables) {
            if (coverage !is BackupCoverage.Table.Included) continue
            val element = firstElement(root, coverage.jsonArray)
            if (element == null) {
                problems += "$table: JSON の ${coverage.jsonArray} が空か無い(テストのデータにも値を入れること)"
                continue
            }
            // ID だけの配列(linkCheckMuted)は、配列があることだけ確かめる
            if (element !is JSONObject) continue
            coverage.columns.forEach { (column, columnCoverage) ->
                if (columnCoverage is BackupCoverage.Column.Included && !element.has(columnCoverage.jsonKey)) {
                    problems += "$table.$column: JSON に ${columnCoverage.jsonKey} が無い(BackupJson に足す)"
                }
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))

        val decoded = BackupJson.decode(text)
        assertEquals(0, decoded.invalidRecords)
        assertEquals(full, decoded.data)
    }

    /** `collections.items` のような名前の配列の、最初の要素 */
    private fun firstElement(root: JSONObject, path: String): Any? {
        var current: Any? = root
        for (name in path.split('.')) {
            val array = when (current) {
                is JSONObject -> current.optJSONArray(name)
                else -> null
            } ?: return null
            current = if (array.length() == 0) return null else array.get(0)
        }
        return current
    }

    private val mainSources: Map<String, String> by lazy {
        val root = File("src/main/java")
        assertTrue(root.isDirectory)
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
    }

    @Test
    fun DataStoreのファイルの扱いが決めてある() {
        val names = Regex("""preferencesDataStore\(name = "([^"]+)"\)""").findAll(mainSources.values.joinToString("\n"))
            .map { it.groupValues[1] }.toSet()
        assertEquals(BackupCoverage.dataStores.keys, names)
        BackupCoverage.dataStores.forEach { (name, reason) -> assertTrue("$name の理由が空", reason == null || reason.isNotBlank()) }
    }

    @Test
    fun DataStoreのすべてのキーの扱いが決めてある() {
        // stringPreferencesKey("name") / stringPreferencesKey("prefix_${...}") の名前(後ろに付け足すものは prefix_*)と型
        val keyPattern = Regex("""(boolean|string|int|long|float|double|stringSet)PreferencesKey\("([^"$]*)(\$)?""")
        val found = mutableMapOf<String, MutableSet<String>>()
        val nonLiteral = mutableSetOf<String>()
        for ((path, text) in mainSources) {
            keyPattern.findAll(text).forEach { match ->
                val name = match.groupValues[2] + if (match.groupValues[3].isNotEmpty()) "*" else ""
                found.getOrPut(name) { mutableSetOf() } += match.groupValues[1]
            }
            if (Regex("""PreferencesKey\((?!")""").containsMatchIn(text)) nonLiteral += path
        }
        // 名前を組み立てるキーは、まるごと含めないと決めた DataStore(処理の所要時間の記録)と、バックアップの設定を戻すところだけ。
        // 戻すところは、BackupCoverage で戻すと決めた名前だけを書く(その確かめがあること)
        val settingsImpl = "com/unchunks/echomark/data/repository/AppSettingsRepositoryImpl.kt"
        assertEquals(setOf("com/unchunks/echomark/data/repository/ProcessingStatsRepositoryImpl.kt", settingsImpl), nonLiteral)
        assertTrue(mainSources.getValue(settingsImpl).contains("when (BackupCoverage.settingTypeOf(name))"))

        val declared = BackupCoverage.dataStoreKeys
        val missing = found.keys.filter { BackupCoverage.keyOf(it.removeSuffix("*").let { name -> if (it.endsWith("*")) "${name}x" else name }) == null }
        assertTrue("扱いの決まっていない DataStore のキー(BackupCoverage.dataStoreKeys に足す): $missing", missing.isEmpty())
        val stale = declared.keys.filter { key ->
            if (key.endsWith("*")) found.keys.none { it.startsWith(key.removeSuffix("*")) } else key !in found
        }
        assertTrue("もう無いキー(BackupCoverage.dataStoreKeys から外す): $stale", stale.isEmpty())
        declared.forEach { (key, coverage) ->
            if (coverage is BackupCoverage.Key.Excluded) assertTrue("$key の理由が空", coverage.reason.isNotBlank())
        }
        // 戻す設定は、ソースのキーと同じ型で決めてある(違うと、戻した値を読めない)
        val sourceType = mapOf("boolean" to BackupCoverage.SettingType.BOOLEAN, "int" to BackupCoverage.SettingType.INT, "string" to BackupCoverage.SettingType.STRING)
        val wrongType = found.filter { (name, types) ->
            val coverage = BackupCoverage.keyOf(if (name.endsWith("*")) name.removeSuffix("*") + "x" else name) as? BackupCoverage.Key.Setting
            coverage != null && types.map { sourceType[it] }.toSet() != setOf(coverage.type)
        }.keys
        assertTrue("型の違う設定: $wrongType", wrongType.isEmpty())
    }

    @Test
    fun 利用者のデータのキーと設定はバックアップに入り_ロックと端末だけの設定は入らない() {
        // 利用者のデータは chat_templates だけ。増やしたら、BackupData と BackupJson に足してここにも足す
        assertEquals(setOf("chat_templates"), BackupCoverage.dataStoreKeys.filterValues { it == BackupCoverage.Key.UserData }.keys)
        val root = JSONObject(BackupJson.encode(full))
        assertTrue(root.getJSONArray("chatTemplates") is JSONArray)
        assertEquals("DARK", root.getJSONObject("settings").getString("theme_mode"))
        // バックアップを戻して、勝手にロック・オフラインモードを変えない。端末だけの設定も戻さない
        listOf("app_lock_enabled", "app_lock_delay", "app_lock_hide_content", "offline_mode", "local_llm_use_gpu", "local_model_summary", "auto_backup_folder")
            .forEach { assertEquals(it, null, BackupCoverage.settingTypeOf(it)) }
    }
}
