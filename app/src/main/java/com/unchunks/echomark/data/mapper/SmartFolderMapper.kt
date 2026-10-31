package com.unchunks.echomark.data.mapper

import com.unchunks.echomark.data.local.entity.SmartFolderEntity
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkQuery
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.CreatedPeriod
import com.unchunks.echomark.domain.bookmark.model.ReadState
import com.unchunks.echomark.domain.bookmark.model.SmartFolder
import com.unchunks.echomark.domain.bookmark.model.TagMatch

/**
 * スマートフォルダの条件([SmartFolderEntity])の複数の値を、1つの文字列に詰める・戻す。
 * 読めない値(新しい版で増えた・消えた名前、数値でない ID)は、その値だけ読み飛ばす。
 */
object SmartFolderCodec {
    private const val LIST_SEPARATOR = ","
    private const val NAME_SEPARATOR = "\n"

    fun encodeIds(ids: Collection<Long>): String = ids.sorted().joinToString(LIST_SEPARATOR)

    fun decodeIds(text: String): Set<Long> =
        text.split(LIST_SEPARATOR).mapNotNull { it.trim().toLongOrNull() }.toCollection(LinkedHashSet())

    fun encodeTypes(types: Collection<BookmarkType>): String =
        BookmarkType.entries.filter { it in types }.joinToString(LIST_SEPARATOR) { it.name }

    fun decodeTypes(text: String): Set<BookmarkType> =
        text.split(LIST_SEPARATOR).mapNotNull { name -> BookmarkType.entries.firstOrNull { it.name == name.trim() } }.toSet()

    /** カテゴリの名前は1行の入力なので、改行で区切る(名前の中の改行は空白にする) */
    fun encodeCategories(categories: Collection<String>): String =
        categories.map { it.replace('\n', ' ').trim() }.filter { it.isNotEmpty() }.sorted().joinToString(NAME_SEPARATOR)

    fun decodeCategories(text: String): Set<String> =
        text.split(NAME_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet())
}

fun SmartFolderEntity.toDomain(): SmartFolder = SmartFolder(
    id = id,
    name = name,
    query = BookmarkQuery(
        filter = BookmarkFilter.entries.firstOrNull { it.name == filter } ?: BookmarkFilter.ACTIVE,
        tagIds = SmartFolderCodec.decodeIds(tagIds),
        tagMatch = TagMatch.entries.firstOrNull { it.name == tagMatch } ?: TagMatch.ALL,
        categories = SmartFolderCodec.decodeCategories(categories),
        types = SmartFolderCodec.decodeTypes(types),
        period = period?.let { name -> CreatedPeriod.entries.firstOrNull { it.name == name } },
        readState = readState?.let { name -> ReadState.entries.firstOrNull { it.name == name } }
    ),
    sortOrder = sortOrder?.let { name -> BookmarkSortOrder.entries.firstOrNull { it.name == name } },
    createdAt = createdAt
)

/** [name]・[query]・[sortOrder] から、保存する行を作る(ID は DB が振る)。 */
fun smartFolderEntity(name: String, query: BookmarkQuery, sortOrder: BookmarkSortOrder?, createdAt: Long): SmartFolderEntity =
    SmartFolderEntity(
        name = name,
        filter = query.filter.name,
        tagIds = SmartFolderCodec.encodeIds(query.tagIds),
        tagMatch = query.tagMatch.name,
        categories = SmartFolderCodec.encodeCategories(query.categories),
        types = SmartFolderCodec.encodeTypes(query.types),
        period = query.period?.name,
        readState = query.readState?.name,
        sortOrder = sortOrder?.name,
        createdAt = createdAt
    )
