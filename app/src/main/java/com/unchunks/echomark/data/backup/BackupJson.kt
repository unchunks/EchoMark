package com.unchunks.echomark.data.backup

import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.local.entity.BookmarkTagCrossRef
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.local.entity.ConversationEntity
import com.unchunks.echomark.data.local.entity.TagEntity
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.model.ChatRole
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.Writer

/**
 * バックアップの中身。DB のテーブルをそのまま写したもの。
 * API キー・端末内モデル・埋め込みベクトル(読み込み後に再生成する)は含めない。
 */
data class BackupData(
    val exportedAt: Long,
    val bookmarks: List<BookmarkEntity>,
    val tags: List<TagEntity>,
    val bookmarkTags: List<BookmarkTagCrossRef>,
    val conversations: List<ConversationEntity>,
    val messages: List<ChatMessageEntity>
)

/** 読み込んだバックアップと、壊れていて読み飛ばした要素の数。 */
data class DecodedBackup(val data: BackupData, val invalidRecords: Int)

/** バックアップとして扱えないファイル。[message] はそのまま画面に出せる文言。 */
class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * バックアップ(JSON)とエンティティの相互変換。Android に依存しない純粋な処理で、単体テストできる。
 *
 * 形式(version 1):
 * ```
 * { "format": "echomark-backup", "version": 1, "exportedAt": 1700000000000,
 *   "bookmarks": [ {全列} ], "tags": [ {id, name} ], "bookmarkTags": [ {bookmarkId, tagId} ],
 *   "conversations": [ {全列} ], "messages": [ {全列。referencedBookmarkIds は数値の配列} ] }
 * ```
 * ID は書き出し元の DB のもの。読み込み側で振り直し、紐付けはその対応表で付け替える。
 */
object BackupJson {
    const val FORMAT = "echomark-backup"
    const val CURRENT_VERSION = 1

    /**
     * [writer] へ書き出す。要素ごとに文字列化して書くので、全体を1つの巨大な文字列にしない。
     * [writer] は閉じない。
     */
    fun write(data: BackupData, writer: Writer) {
        writer.write("{")
        writer.write("\"format\":${JSONObject.quote(FORMAT)},")
        writer.write("\"version\":$CURRENT_VERSION,")
        writer.write("\"exportedAt\":${data.exportedAt},")
        writeArray(writer, "bookmarks", data.bookmarks) { it.toJson() }
        writer.write(",")
        writeArray(writer, "tags", data.tags) { it.toJson() }
        writer.write(",")
        writeArray(writer, "bookmarkTags", data.bookmarkTags) { it.toJson() }
        writer.write(",")
        writeArray(writer, "conversations", data.conversations) { it.toJson() }
        writer.write(",")
        writeArray(writer, "messages", data.messages) { it.toJson() }
        writer.write("}")
        writer.flush()
    }

    /** テスト・小さなデータ用。 */
    fun encode(data: BackupData): String = java.io.StringWriter().also { write(data, it) }.toString()

    /**
     * JSON 文字列を読み込む。形式が違う・新しすぎる場合は [BackupFormatException]。
     * 個々の要素が壊れている(必須項目が無い、未知の種類)場合はその要素だけ読み飛ばし、数を返す。
     */
    fun decode(text: String): DecodedBackup {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw BackupFormatException("ファイルを読み取れませんでした(JSON の形式が正しくありません)", e)
        }
        if (root.optString("format") != FORMAT) {
            throw BackupFormatException("EchoMark のバックアップファイルではありません")
        }
        val version = root.optInt("version", -1)
        if (version < 1) throw BackupFormatException("バックアップのバージョンを判別できませんでした")
        if (version > CURRENT_VERSION) {
            throw BackupFormatException("新しいバージョンのアプリで作られたバックアップです。アプリを更新してから読み込んでください")
        }

        var invalid = 0
        fun <T> readArray(name: String, parse: (JSONObject) -> T?): List<T> {
            val array = root.optJSONArray(name) ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val parsed = try {
                    array.optJSONObject(index)?.let(parse)
                } catch (e: JSONException) {
                    null
                }
                if (parsed == null) invalid++
                parsed
            }
        }

        val data = BackupData(
            exportedAt = root.optLong("exportedAt", 0L),
            bookmarks = readArray("bookmarks") { it.toBookmark() },
            tags = readArray("tags") { it.toTag() },
            bookmarkTags = readArray("bookmarkTags") { it.toCrossRef() },
            conversations = readArray("conversations") { it.toConversation() },
            messages = readArray("messages") { it.toMessage() }
        )
        return DecodedBackup(data, invalid)
    }

    private fun <T> writeArray(writer: Writer, name: String, items: List<T>, toJson: (T) -> JSONObject) {
        writer.write("${JSONObject.quote(name)}:[")
        items.forEachIndexed { index, item ->
            if (index > 0) writer.write(",")
            writer.write(toJson(item).toString())
        }
        writer.write("]")
    }

    // ---- エンティティ → JSON ----

    private fun BookmarkEntity.toJson() = JSONObject()
        .put("id", id)
        .put("type", type.name)
        .putNullable("content", content)
        .putNullable("contentUri", contentUri)
        .put("title", title)
        .putNullable("summary", summary)
        .putNullable("category", category)
        .put("createdAt", createdAt)
        .put("lastAccessedAt", lastAccessedAt)
        .put("aiStatus", aiStatus.name)
        .putNullable("imageUrl", imageUrl)
        .putNullable("siteName", siteName)
        .put("isFavorite", isFavorite)
        .put("isArchived", isArchived)

    private fun TagEntity.toJson() = JSONObject().put("id", id).put("name", name)

    private fun BookmarkTagCrossRef.toJson() = JSONObject().put("bookmarkId", bookmarkId).put("tagId", tagId)

    private fun ConversationEntity.toJson() = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("isTitleManuallySet", isTitleManuallySet)
        .putNullable("summary", summary)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    private fun ChatMessageEntity.toJson() = JSONObject()
        .put("id", id)
        .put("conversationId", conversationId)
        .put("role", role.name)
        .put("content", content)
        .put("referencedBookmarkIds", JSONArray(parseReferencedIds(referencedBookmarkIds)))
        .put("createdAt", createdAt)

    // ---- JSON → エンティティ(必須項目が無い・未知の種類なら null) ----

    private fun JSONObject.toBookmark(): BookmarkEntity? {
        val type = enumOrNull<BookmarkType>(optString("type")) ?: return null
        val title = stringOrNull("title") ?: return null
        val createdAt = longOrNull("createdAt") ?: return null
        return BookmarkEntity(
            id = longOrNull("id") ?: return null,
            type = type,
            content = stringOrNull("content"),
            contentUri = stringOrNull("contentUri")?.takeIf { it.isNotBlank() },
            title = title,
            summary = stringOrNull("summary"),
            category = stringOrNull("category"),
            createdAt = createdAt,
            lastAccessedAt = longOrNull("lastAccessedAt") ?: createdAt,
            // 未知の状態(将来の版で増えた場合など)は「AI の準備待ち」として再処理の対象にする
            aiStatus = enumOrNull<AiStatus>(optString("aiStatus")) ?: AiStatus.WAITING_MODEL,
            imageUrl = stringOrNull("imageUrl"),
            siteName = stringOrNull("siteName"),
            isFavorite = optBoolean("isFavorite", false),
            isArchived = optBoolean("isArchived", false)
        )
    }

    private fun JSONObject.toTag(): TagEntity? {
        val name = stringOrNull("name")?.takeIf { it.isNotBlank() } ?: return null
        return TagEntity(id = longOrNull("id") ?: return null, name = name)
    }

    private fun JSONObject.toCrossRef(): BookmarkTagCrossRef? = BookmarkTagCrossRef(
        bookmarkId = longOrNull("bookmarkId") ?: return null,
        tagId = longOrNull("tagId") ?: return null
    )

    private fun JSONObject.toConversation(): ConversationEntity? {
        val createdAt = longOrNull("createdAt") ?: return null
        return ConversationEntity(
            id = longOrNull("id") ?: return null,
            title = stringOrNull("title") ?: return null,
            isTitleManuallySet = optBoolean("isTitleManuallySet", false),
            summary = stringOrNull("summary"),
            createdAt = createdAt,
            updatedAt = longOrNull("updatedAt") ?: createdAt
        )
    }

    private fun JSONObject.toMessage(): ChatMessageEntity? {
        val ids = optJSONArray("referencedBookmarkIds")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                if (array.isNull(index)) null else array.optLong(index, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
            }
        }.orEmpty()
        return ChatMessageEntity(
            id = longOrNull("id") ?: return null,
            conversationId = longOrNull("conversationId") ?: return null,
            role = enumOrNull<ChatRole>(optString("role")) ?: return null,
            content = stringOrNull("content") ?: return null,
            referencedBookmarkIds = formatReferencedIds(ids),
            createdAt = longOrNull("createdAt") ?: return null
        )
    }

    private fun JSONObject.putNullable(name: String, value: String?): JSONObject =
        if (value == null) this else put(name, value)

    private fun JSONObject.stringOrNull(name: String): String? =
        if (isNull(name)) null else opt(name) as? String

    private fun JSONObject.longOrNull(name: String): Long? =
        if (isNull(name)) null else (opt(name) as? Number)?.toLong()

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }
}

/** ChatMessageEntity.referencedBookmarkIds(カンマ区切り)を ID の一覧にする。 */
internal fun parseReferencedIds(value: String?): List<Long> =
    value?.split(",")?.mapNotNull { it.trim().toLongOrNull() }.orEmpty()

/** ID の一覧を ChatMessageEntity.referencedBookmarkIds の形式にする。空なら null。 */
internal fun formatReferencedIds(ids: List<Long>): String? =
    ids.takeIf { it.isNotEmpty() }?.joinToString(",")
