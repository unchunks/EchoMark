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

/** 読んだかどうかによる絞り込み(#60)。 */
enum class ReadState {
    /** まだ読んでいない(詳細画面を開いていない・未読に戻した) */
    UNREAD,

    /** 読んだ */
    READ
}

/**
 * 保存した日からの期間による絞り込み。「いつ頃保存したか」で探すときに使う(日付を選ばせず、スマートフォルダに保存しても
 * 開くたびに今日から数え直せるよう、相対の期間にしている)。
 * @property days 今日から何日さかのぼるか
 */
enum class CreatedPeriod(val days: Int) {
    PAST_WEEK(7),
    PAST_MONTH(30),
    PAST_3_MONTHS(90),
    PAST_YEAR(365);

    /** [now] から数えて、この期間の始まりの時刻(ミリ秒)。これ以後に保存したものが対象 */
    fun sinceMillis(now: Long): Long = now - days * MILLIS_PER_DAY

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}

/**
 * 一覧の絞り込み条件。画面や DB の形を持たない素のデータで、一覧の絞り込み・検索の範囲・(スマートフォルダのような)
 * 条件の保存が、同じ条件を使えるようにしている。並べ替えと検索語は条件に含めない。
 * @property filter 通常 / アーカイブ / お気に入り
 * @property tagIds 選んだタグ。[tagMatch] で組み合わせる。各タグは、階層でその下にあるタグが付いていれば満たす。無ければタグでは絞らない
 * @property tagMatch [tagIds] が複数のときの組み合わせ方。1つ以下のときは意味を持たない
 * @property categories 選んだカテゴリ(名前。どれかであればよい。ブックマークのカテゴリは1つだけなので AND は無い)。無ければカテゴリでは絞らない。
 *   大文字小文字の違いは区別しない
 * @property types 選んだ種類(リンク・メモ・画像など。どれかであればよい)。無ければ種類では絞らない
 * @property period 保存した日からの期間。null なら期間では絞らない
 * @property readState 読んだかどうか。null なら絞らない
 */
data class BookmarkQuery(
    val filter: BookmarkFilter = BookmarkFilter.ACTIVE,
    val tagIds: Set<Long> = emptySet(),
    val tagMatch: TagMatch = TagMatch.ALL,
    val categories: Set<String> = emptySet(),
    val types: Set<BookmarkType> = emptySet(),
    val period: CreatedPeriod? = null,
    val readState: ReadState? = null
) {
    /** タグ・カテゴリ・種類・期間・読んだかのどれかで絞っているか(「すべて解除」で外せる条件があるか)。通常/アーカイブ/お気に入りは含まない。 */
    val isNarrowed: Boolean
        get() = tagIds.isNotEmpty() || categories.isNotEmpty() || types.isNotEmpty() || period != null || readState != null

    fun toggleTag(tagId: Long): BookmarkQuery =
        copy(tagIds = if (tagId in tagIds) tagIds - tagId else tagIds + tagId)

    fun toggleCategory(name: String): BookmarkQuery {
        val same = categories.firstOrNull { it.equals(name, ignoreCase = true) }
        return copy(categories = if (same != null) categories - same else categories + name)
    }

    fun toggleType(type: BookmarkType): BookmarkQuery =
        copy(types = if (type in types) types - type else types + type)

    /** 期間を選ぶ。すでに選んでいる期間をもう一度選ぶと外れる。 */
    fun togglePeriod(selected: CreatedPeriod): BookmarkQuery = copy(period = if (period == selected) null else selected)

    /** 読んだかを選ぶ。すでに選んでいるものをもう一度選ぶと外れる。 */
    fun toggleReadState(selected: ReadState): BookmarkQuery = copy(readState = if (readState == selected) null else selected)

    /** タグ・カテゴリ・種類・期間・読んだかの条件をすべて外す(タグの組み合わせ方も初期値に戻す)。通常 / アーカイブ / お気に入りは変えない。 */
    fun clearConditions(): BookmarkQuery = copy(
        tagIds = emptySet(), tagMatch = TagMatch.ALL, categories = emptySet(), types = emptySet(), period = null, readState = null
    )
}

/** 一覧の見せ方(#62)。並べ替え・絞り込みとは別に、端末に保存して次に開いたときも同じ見せ方にする。 */
enum class BookmarkListDisplay {
    /** カード(サムネイル・要約・タグまで見せる。既定) */
    CARD,

    /** コンパクト(サムネイルを小さくし、タイトルと出どころだけの1件1行にして、たくさん並べる) */
    COMPACT,

    /** グリッド(大きなサムネイルとタイトルを、2列以上のタイルで並べる) */
    GRID
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
