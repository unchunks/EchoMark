package com.unchunks.echomark.data.backup

import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.data.attachment.isValidAttachmentPath
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.local.entity.BookmarkTagCrossRef
import com.unchunks.echomark.data.local.entity.CategoryEntity
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.local.entity.ConversationEntity
import com.unchunks.echomark.data.local.entity.RemovedTagEntity
import com.unchunks.echomark.data.local.entity.SmartFolderEntity
import com.unchunks.echomark.data.mapper.SmartFolderCodec
import com.unchunks.echomark.data.mapper.smartFolderEntity
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkQuery
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.bookmark.model.CreatedPeriod
import com.unchunks.echomark.domain.bookmark.model.ReadState
import com.unchunks.echomark.domain.bookmark.model.SmartFolderNames
import com.unchunks.echomark.domain.bookmark.model.TagMatch
import com.unchunks.echomark.data.local.entity.TagAliasEntity
import com.unchunks.echomark.data.local.entity.TagEntity
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.model.TagNames
import com.unchunks.echomark.domain.model.TagSource
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.Writer

/**
 * バックアップの中身。DB のテーブルをそのまま写したもの。
 * API キー・端末内モデル・埋め込みベクトル(読み込み後に再生成する)・添付ファイルの本体
 * (画像・PDF・音声など。ファイル名などの情報だけを含める)は含めない。
 */
data class BackupData(
    val exportedAt: Long,
    val bookmarks: List<BookmarkEntity>,
    val tags: List<TagEntity>,
    val bookmarkTags: List<BookmarkTagCrossRef>,
    val conversations: List<ConversationEntity>,
    val messages: List<ChatMessageEntity>,
    /** タグの別名(統合・名前変更した元の名前)。[TagAliasEntity.tagId] は [tags] の ID */
    val tagAliases: List<TagAliasEntity> = emptyList(),
    /** ユーザーが外したタグの記録。[RemovedTagEntity.bookmarkId] は [bookmarks] の ID */
    val removedTags: List<RemovedTagEntity> = emptyList(),
    /** ユーザーが作ったカテゴリ(名前と説明)。ID は持たない(読み込み側で振り直す) */
    val categories: List<CategoryEntity> = emptyList(),
    /** スマートフォルダ(保存した絞り込み条件)。タグは ID が端末ごとに変わるので、名前で持つ */
    val smartFolders: List<SmartFolderBackup> = emptyList()
)

/**
 * バックアップに入れるスマートフォルダ(#34)。DB の行([SmartFolderEntity])と違い、タグを ID ではなく名前で持つ
 * (タグの ID は読み込む端末ごとに振り直されるため。読み込み側で名前から探し直す)。
 * 列挙の値は文字列のまま持ち、読み込み側で知っているものだけを使う(新しい版で増えた値で読み込みを失敗させない)。
 */
data class SmartFolderBackup(
    val name: String,
    val filter: String = BookmarkFilter.ACTIVE.name,
    val tagNames: List<String> = emptyList(),
    val tagMatch: String = TagMatch.ALL.name,
    val categories: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val period: String? = null,
    val readState: String? = null,
    val sortOrder: String? = null,
    val createdAt: Long = 0L
)

/** DB の行を、タグを名前にしたバックアップ用にする。もう無いタグ(削除済み)の ID は外す。 */
fun SmartFolderEntity.toBackup(tagNameById: Map<Long, String>): SmartFolderBackup = SmartFolderBackup(
    name = name,
    filter = filter,
    tagNames = SmartFolderCodec.decodeIds(tagIds).mapNotNull { tagNameById[it]?.takeIf { name -> name.isNotBlank() } },
    tagMatch = tagMatch,
    categories = SmartFolderCodec.decodeCategories(categories).toList(),
    types = SmartFolderCodec.decodeTypes(types).map { it.name },
    period = period,
    readState = readState,
    sortOrder = sortOrder,
    createdAt = createdAt
)

/** バックアップのスマートフォルダを、この端末のタグの ID([tagIds])を入れた DB の行にする。知らない値は既定に戻す。 */
fun SmartFolderBackup.toEntity(tagIds: Collection<Long>): SmartFolderEntity = smartFolderEntity(
    name = name,
    query = BookmarkQuery(
        filter = BookmarkFilter.entries.firstOrNull { it.name == filter } ?: BookmarkFilter.ACTIVE,
        tagIds = tagIds.toSet(),
        tagMatch = TagMatch.entries.firstOrNull { it.name == tagMatch } ?: TagMatch.ALL,
        categories = categories.toSet(),
        types = SmartFolderCodec.decodeTypes(types.joinToString(",")),
        period = period?.let { value -> CreatedPeriod.entries.firstOrNull { it.name == value } },
        readState = readState?.let { value -> ReadState.entries.firstOrNull { it.name == value } }
    ),
    sortOrder = sortOrder?.let { value -> BookmarkSortOrder.entries.firstOrNull { it.name == value } },
    createdAt = createdAt
)

/** 読み込んだバックアップと、壊れていて読み飛ばした要素の数。 */
data class DecodedBackup(val data: BackupData, val invalidRecords: Int)

/** バックアップとして扱えないファイル。[message] はそのまま画面に出せる文言。 */
class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * バックアップ(JSON)とエンティティの相互変換。Android に依存しない純粋な処理で、単体テストできる。
 *
 * 形式(version 11):
 * ```
 * { "format": "echomark-backup", "version": 7, "exportedAt": 1700000000000,
 *   "bookmarks": [ {全列。summaryEdited・categoryEdited は手で直した・選んだときだけ} ], "tags": [ {id, name, isUserCreated, parentId は親があるときだけ} ],
 *   "bookmarkTags": [ {bookmarkId, tagId, source("USER" / "AI")} ],
 *   "conversations": [ {全列。aboutBookmarkId・summaryCoveredUntil・branchedFromId・branchedAt は無ければ省略} ],
 *   "messages": [ {全列。referencedBookmarkIds は数値の配列} ],
 *   "tagAliases": [ {alias, tagId} ], "removedTags": [ {bookmarkId, tagKey, tagName, removedAt} ],
 *   "categories": [ {name, description, createdAt} ],
 *   "smartFolders": [ {name, filter, tagNames, tagMatch, categories, types, period と readState と sortOrder は無ければ省略, createdAt} ] }
 * ```
 * ID は書き出し元の DB のもの。読み込み側で振り直し、紐付けはその対応表で付け替える。
 * 処理の段・日時・失敗の理由(DB v12 の processingStage など)は端末の中の一時的な状態のため書き出さず、読み込んだ
 * ブックマークでは空になる(処理待ち・処理中だったものは準備待ちとして読み、再処理する)。
 *
 * 版の履歴(古い版もそのまま読み込める):
 * - 1: 最初の形式
 * - 2: 会話に aboutBookmarkId(「このブックマークについて質問」の対象)を追加。1 では全て通常の会話として読む
 * - 3: タグに isUserCreated、紐付けに source(誰が付けたか)を追加。2 以前では全てユーザーのタグとして読む
 *   (DB のマイグレーションと同じく、ユーザーのタグを AI のものと誤って消さないため)
 * - 4: ブックマークに添付ファイルの情報(filePath・mimeType・fileName・fileSize)を追加。本体は含めないため、
 *   読み込む端末にファイルが無ければ filePath を外す(読み込む側で確かめる)。3 以前ではファイルなしとして読む
 * - 5: ブックマークに contentFetchedAt(URL の本文を取得できた日時)を追加。4 以前では DB のマイグレーションと同じく、
 *   本文のある URL は保存日時で取得済み、それ以外は未取得として読む
 * - 6: 会話に summaryCoveredUntil(要約に含めた最後の発言の日時)を追加。5 以前では要約はまだ無いものとして読む
 * - 7: ブックマークに summaryEdited(要約をユーザーが手で直した印。再処理しても要約を置き換えない)を追加。6 以前では手で直していないものとして読む。
 *   要約の履歴(DB v15 の summary_history)は端末の中の記録のため含めない
 * - 8: タグに parentId(階層の親のタグ。書き出し元の ID)を追加。7 以前は階層が無いものとして読む。
 *   親が無い・自分自身・輪になる parentId は、読み込むときに外す(読み込み側でも確かめる)
 * - 9: タグの別名(tagAliases。DB v17 の tag_aliases)と、ユーザーが外したタグの記録(removedTags。removed_tags)を追加。
 *   8 以前は別名も記録も無いものとして読む。別名のキー(aliasKey)は alias から [TagNames.key] で作り直す
 * - 10: ユーザーが作ったカテゴリ(categories。DB v18 の categories)と、ブックマークの categoryEdited(カテゴリを自分で選んだ印)を追加。
 *   9 以前は、カテゴリは無く、どのブックマークのカテゴリも AI が付けたものとして読む
 * - 11: 会話に isPinned(一覧の先頭に固定)と、branchedFromId・branchedAt(分けた元の会話)を追加。10 以前は固定していない・
 *   分けていない会話として読む。branchedFromId は書き出し元の ID で、読み込むときに付け替える(元が無ければ通常の会話にする)
 * - 12: ブックマークに readAt(読んだ日時。無ければ未読)を追加。11 以前は、保存したあとに開いたことがあるものだけ既読として読む
 * - 13: スマートフォルダ(smartFolders。DB v21 の smart_folders)を追加。12 以前は無いものとして読む。
 *   タグは ID ではなく名前で持ち、読み込むときに名前でこの端末のタグを探す(無ければ作る)。同じ名前のフォルダが既にあれば追加しない
 */
object BackupJson {
    const val FORMAT = "echomark-backup"
    const val CURRENT_VERSION = 13

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
        writer.write(",")
        writeArray(writer, "tagAliases", data.tagAliases) { it.toJson() }
        writer.write(",")
        writeArray(writer, "removedTags", data.removedTags) { it.toJson() }
        writer.write(",")
        writeArray(writer, "categories", data.categories) { it.toJson() }
        writer.write(",")
        writeArray(writer, "smartFolders", data.smartFolders) { it.toJson() }
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
            bookmarks = readArray("bookmarks") { it.toBookmark(version) },
            tags = readArray("tags") { it.toTag() },
            bookmarkTags = readArray("bookmarkTags") { it.toCrossRef() },
            conversations = readArray("conversations") { it.toConversation() },
            messages = readArray("messages") { it.toMessage() },
            tagAliases = readArray("tagAliases") { it.toTagAlias() },
            removedTags = readArray("removedTags") { it.toRemovedTag() },
            categories = readArray("categories") { it.toCategory() },
            smartFolders = readArray("smartFolders") { it.toSmartFolder() }
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
        .apply { if (summaryEdited) put("summaryEdited", true) }
        .apply { if (categoryEdited) put("categoryEdited", true) }
        .put("createdAt", createdAt)
        .put("lastAccessedAt", lastAccessedAt)
        .put("aiStatus", aiStatus.name)
        .putNullable("imageUrl", imageUrl)
        .putNullable("siteName", siteName)
        .put("isFavorite", isFavorite)
        .put("isArchived", isArchived)
        .putNullable("filePath", filePath)
        .putNullable("mimeType", mimeType)
        .putNullable("fileName", fileName)
        .apply { fileSize?.let { put("fileSize", it) } }
        .apply { contentFetchedAt?.let { put("contentFetchedAt", it) } }
        .apply { readAt?.let { put("readAt", it) } }

    private fun TagEntity.toJson() = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("isUserCreated", isUserCreated)
        .apply { parentId?.let { put("parentId", it) } }

    private fun CategoryEntity.toJson() = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("createdAt", createdAt)

    private fun TagAliasEntity.toJson() = JSONObject().put("alias", alias).put("tagId", tagId)

    private fun RemovedTagEntity.toJson() = JSONObject()
        .put("bookmarkId", bookmarkId)
        .put("tagKey", tagKey)
        .put("tagName", tagName)
        .put("removedAt", removedAt)

    private fun BookmarkTagCrossRef.toJson() = JSONObject()
        .put("bookmarkId", bookmarkId)
        .put("tagId", tagId)
        .put("source", source.name)

    private fun ConversationEntity.toJson() = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("isTitleManuallySet", isTitleManuallySet)
        .putNullable("summary", summary)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .apply { aboutBookmarkId?.let { put("aboutBookmarkId", it) } }
        .apply { summaryCoveredUntil?.let { put("summaryCoveredUntil", it) } }
        .put("isPinned", isPinned)
        .apply { branchedFromId?.let { put("branchedFromId", it) } }
        .apply { branchedAt?.let { put("branchedAt", it) } }

    private fun ChatMessageEntity.toJson() = JSONObject()
        .put("id", id)
        .put("conversationId", conversationId)
        .put("role", role.name)
        .put("content", content)
        .put("referencedBookmarkIds", JSONArray(parseReferencedIds(referencedBookmarkIds)))
        .put("createdAt", createdAt)

    // ---- JSON → エンティティ(必須項目が無い・未知の種類なら null) ----

    private fun JSONObject.toBookmark(version: Int): BookmarkEntity? {
        val type = enumOrNull<BookmarkType>(optString("type")) ?: return null
        val title = stringOrNull("title") ?: return null
        val createdAt = longOrNull("createdAt") ?: return null
        val contentUri = stringOrNull("contentUri")?.takeIf { it.isNotBlank() }
        // URL はブラウザで開く・本文を取得するため、http/https 以外(intent:, file:, content: など)は受け付けない
        if (type == BookmarkType.URL && (contentUri == null || !isWebUrl(contentUri))) return null
        return BookmarkEntity(
            id = longOrNull("id") ?: return null,
            type = type,
            content = stringOrNull("content"),
            contentUri = contentUri,
            title = title,
            summary = stringOrNull("summary"),
            // version 6 以前には無い(手で直していないものとして読む)
            summaryEdited = optBoolean("summaryEdited", false),
            // version 9 以前には無い(AI が付けたカテゴリとして読む)
            categoryEdited = optBoolean("categoryEdited", false),
            category = stringOrNull("category"),
            createdAt = createdAt,
            lastAccessedAt = longOrNull("lastAccessedAt") ?: createdAt,
            // 未知の状態(将来の版で増えた場合など)は「AI の準備待ち」として再処理の対象にする
            aiStatus = enumOrNull<AiStatus>(optString("aiStatus")) ?: AiStatus.WAITING_MODEL,
            // OG 画像は読み込むため http/https だけを残す(不正なら画像なしにする)
            imageUrl = stringOrNull("imageUrl")?.takeIf { isWebUrl(it) },
            siteName = stringOrNull("siteName"),
            isFavorite = optBoolean("isFavorite", false),
            isArchived = optBoolean("isArchived", false),
            // 添付ファイルの置き場所以外を指すパス(書き換えられたファイルなど)は受け付けない
            filePath = stringOrNull("filePath")?.takeIf { isValidAttachmentPath(it) },
            mimeType = stringOrNull("mimeType"),
            fileName = stringOrNull("fileName"),
            fileSize = longOrNull("fileSize")?.takeIf { it >= 0 },
            // version 11 以前には無い。DB のマイグレーションと同じく、保存したあとに開いたことがあるものだけ既読にする
            readAt = if (version >= 12) {
                longOrNull("readAt")
            } else {
                (longOrNull("lastAccessedAt") ?: createdAt).takeIf { it > createdAt }
            },
            contentFetchedAt = if (version >= 5) {
                longOrNull("contentFetchedAt")
            } else {
                // version 4 以前には無い。本文があれば取得済みとみなしていたので、それに合わせる
                createdAt.takeIf { type == BookmarkType.URL && !stringOrNull("content").isNullOrBlank() }
            }
        )
    }

    private fun JSONObject.toTag(): TagEntity? {
        val name = stringOrNull("name")?.takeIf { it.isNotBlank() } ?: return null
        return TagEntity(
            id = longOrNull("id") ?: return null,
            name = name,
            // version 2 以前には無い(ユーザーのタグとして読む)
            isUserCreated = optBoolean("isUserCreated", true),
            // version 7 以前には無い(最上位として読む)
            parentId = longOrNull("parentId")
        )
    }

    private fun JSONObject.toCrossRef(): BookmarkTagCrossRef? = BookmarkTagCrossRef(
        bookmarkId = longOrNull("bookmarkId") ?: return null,
        tagId = longOrNull("tagId") ?: return null,
        // version 2 以前には無い。未知の値(将来の版で増えた場合など)も、消されないようユーザーのものとして読む
        source = enumOrNull<TagSource>(optString("source")) ?: TagSource.USER
    )

    private fun SmartFolderBackup.toJson() = JSONObject()
        .put("name", name)
        .put("filter", filter)
        .put("tagNames", JSONArray(tagNames))
        .put("tagMatch", tagMatch)
        .put("categories", JSONArray(categories))
        .put("types", JSONArray(types))
        .apply { period?.let { put("period", it) } }
        .apply { readState?.let { put("readState", it) } }
        .apply { sortOrder?.let { put("sortOrder", it) } }
        .put("createdAt", createdAt)

    private fun JSONObject.toSmartFolder(): SmartFolderBackup? {
        val name = stringOrNull("name")?.let { SmartFolderNames.normalize(it) } ?: return null
        fun strings(key: String): List<String> {
            val array = optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).mapNotNull { array.optString(it, "").trim().takeIf { value -> value.isNotEmpty() } }
        }
        return SmartFolderBackup(
            name = name,
            filter = stringOrNull("filter") ?: BookmarkFilter.ACTIVE.name,
            tagNames = strings("tagNames"),
            tagMatch = stringOrNull("tagMatch") ?: TagMatch.ALL.name,
            categories = strings("categories"),
            types = strings("types"),
            period = stringOrNull("period"),
            readState = stringOrNull("readState"),
            sortOrder = stringOrNull("sortOrder"),
            createdAt = longOrNull("createdAt") ?: 0L
        )
    }

    private fun JSONObject.toCategory(): CategoryEntity? {
        val name = stringOrNull("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return CategoryEntity(
            name = name,
            description = stringOrNull("description").orEmpty().trim(),
            createdAt = longOrNull("createdAt") ?: 0L
        )
    }

    private fun JSONObject.toTagAlias(): TagAliasEntity? {
        val alias = stringOrNull("alias")?.let { TagNames.clean(it) }?.takeIf { it.isNotEmpty() } ?: return null
        return TagAliasEntity(aliasKey = TagNames.key(alias), alias = alias, tagId = longOrNull("tagId") ?: return null)
    }

    private fun JSONObject.toRemovedTag(): RemovedTagEntity? {
        val tagName = stringOrNull("tagName")?.let { TagNames.clean(it) }?.takeIf { it.isNotEmpty() } ?: return null
        return RemovedTagEntity(
            bookmarkId = longOrNull("bookmarkId") ?: return null,
            tagKey = TagNames.key(tagName),
            tagName = tagName,
            removedAt = longOrNull("removedAt") ?: 0L
        )
    }

    private fun JSONObject.toConversation(): ConversationEntity? {
        val createdAt = longOrNull("createdAt") ?: return null
        return ConversationEntity(
            id = longOrNull("id") ?: return null,
            title = stringOrNull("title") ?: return null,
            isTitleManuallySet = optBoolean("isTitleManuallySet", false),
            summary = stringOrNull("summary"),
            createdAt = createdAt,
            updatedAt = longOrNull("updatedAt") ?: createdAt,
            // version 1 には無い(通常の会話として読む)
            aboutBookmarkId = longOrNull("aboutBookmarkId"),
            // version 5 以前には無い(要約はまだ無いものとして読む)
            summaryCoveredUntil = longOrNull("summaryCoveredUntil"),
            // version 10 以前には無い(固定していない・分けていない会話として読む)
            isPinned = optBoolean("isPinned", false),
            branchedFromId = longOrNull("branchedFromId"),
            branchedAt = longOrNull("branchedAt")
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

    private val WEB_URL = Regex("^https?://[^\\s/?#]+.*", RegexOption.IGNORE_CASE)

    private fun isWebUrl(value: String): Boolean = WEB_URL.matches(value)
}

/** ChatMessageEntity.referencedBookmarkIds(カンマ区切り)を ID の一覧にする。 */
internal fun parseReferencedIds(value: String?): List<Long> =
    value?.split(",")?.mapNotNull { it.trim().toLongOrNull() }.orEmpty()

/** ID の一覧を ChatMessageEntity.referencedBookmarkIds の形式にする。空なら null。 */
internal fun formatReferencedIds(ids: List<Long>): String? =
    ids.takeIf { it.isNotEmpty() }?.joinToString(",")
