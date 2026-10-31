package com.unchunks.echomark.domain.bookmark.model

/** 一覧に表示するブックマークの絞り込み。 */
enum class BookmarkFilter {
    /** アーカイブしたものを除いたすべて(通常の一覧) */
    ACTIVE,
    /** アーカイブしたものだけ */
    ARCHIVED,
    /** お気に入りだけ(アーカイブしたものは除く) */
    FAVORITES
}

/** 複数のタグを選んだときの組み合わせ方。 */
enum class TagMatch {
    /** 選んだタグのすべてを持つ(AND)。どのタグも、階層でその下にあるタグが付いていればよい */
    ALL,

    /** 選んだタグのどれかを持つ(OR)。階層でその下にあるタグでもよい */
    ANY
}

/**
 * 一覧の絞り込み条件。画面や DB の形を持たない素のデータで、一覧の絞り込み・検索の範囲・(スマートフォルダのような)
 * 条件の保存が、同じ条件を使えるようにしている。並べ替えと検索語は条件に含めない。
 * @property filter 通常 / アーカイブ / お気に入り
 * @property tagIds 選んだタグ。[tagMatch] で組み合わせる。各タグは、階層でその下にあるタグが付いていれば満たす。無ければタグでは絞らない
 * @property tagMatch [tagIds] が複数のときの組み合わせ方。1つ以下のときは意味を持たない
 * @property categories 選んだカテゴリ(名前。どれかであればよい。ブックマークのカテゴリは1つだけなので AND は無い)。無ければカテゴリでは絞らない。
 *   大文字小文字の違いは区別しない
 */
data class BookmarkQuery(
    val filter: BookmarkFilter = BookmarkFilter.ACTIVE,
    val tagIds: Set<Long> = emptySet(),
    val tagMatch: TagMatch = TagMatch.ALL,
    val categories: Set<String> = emptySet()
) {
    /** タグかカテゴリで絞っているか(「すべて解除」で外せる条件があるか)。 */
    val hasTagOrCategory: Boolean get() = tagIds.isNotEmpty() || categories.isNotEmpty()

    fun toggleTag(tagId: Long): BookmarkQuery =
        copy(tagIds = if (tagId in tagIds) tagIds - tagId else tagIds + tagId)

    fun toggleCategory(name: String): BookmarkQuery {
        val same = categories.firstOrNull { it.equals(name, ignoreCase = true) }
        return copy(categories = if (same != null) categories - same else categories + name)
    }

    /** タグとカテゴリの条件をすべて外す(組み合わせ方も初期値に戻す)。通常 / アーカイブ / お気に入りは変えない。 */
    fun clearTagsAndCategories(): BookmarkQuery = copy(tagIds = emptySet(), tagMatch = TagMatch.ALL, categories = emptySet())
}

/** 一覧の並べ替え。 */
enum class BookmarkSortOrder {
    /** 保存日時が新しい順 */
    NEWEST,
    /** 保存日時が古い順 */
    OLDEST,
    /** 最近開いた順(lastAccessedAt の降順) */
    RECENTLY_OPENED,
    /** タイトル順(大文字小文字を区別しない昇順) */
    TITLE
}

/** 検索語の区切り(半角・全角の空白) */
private val SEARCH_TERM_SEPARATOR = Regex("[\\s\\u3000]+")

/**
 * 検索語を空白(半角・全角)で語に分ける。キーワード検索はすべての語を含むもの(AND)を探す。
 * 重複した語は1つにまとめ、空白だけなら空。
 */
fun splitSearchTerms(query: String): List<String> =
    query.split(SEARCH_TERM_SEPARATOR).filter { it.isNotEmpty() }.distinct()
