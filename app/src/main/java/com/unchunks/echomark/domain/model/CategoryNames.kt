package com.unchunks.echomark.domain.model

/** カテゴリ名の扱いと、標準のカテゴリ・分類の説明。 */
object CategoryNames {

    /** カテゴリ名の最大の文字数(端末内 AI が返せる長さに合わせる。AnalysisParser の上限) */
    const val MAX_NAME_CHARS = 12

    /** 説明の最大の文字数(AI に渡すので短くする) */
    const val MAX_DESCRIPTION_CHARS = 80

    /** AI がまだ分類していない・分類できなかったときのカテゴリ(AnalysisParser.DEFAULT_CATEGORY と同じ) */
    const val UNCATEGORIZED = "未分類"

    /** 名前を整える: 前後の空白を除き、途中の連続した空白は1つにする。 */
    fun clean(name: String): String = name.trim().replace(Regex("[\\s\\u3000]+"), " ")

    /** 同じカテゴリとみなすためのキー(全角/半角・大文字/小文字を区別しない)。 */
    fun key(name: String): String = TagNames.key(clean(name))

    /**
     * AI が答えたカテゴリを、選べるカテゴリ([defined])の名前にそろえる(全角/半角・大文字/小文字の違いは同じとみなす)。
     * 当てはまるものが無ければ、整えた答えのまま使う(AI が自由に付けた分類)。
     */
    fun resolve(answer: String, defined: List<String>): String {
        val cleaned = clean(answer)
        val key = key(cleaned)
        return defined.firstOrNull { key(it) == key } ?: cleaned
    }

    /** 標準のカテゴリ(AI の分類の例になっているもの)と、その説明。 */
    val BUILT_IN: List<CategoryDefinition> = listOf(
        CategoryDefinition("技術", "プログラミング・IT・ガジェットなど、技術に関する内容"),
        CategoryDefinition("ニュース", "時事・出来事・業界の動きを伝える内容"),
        CategoryDefinition("レシピ", "料理の作り方、食材や調理に関する内容"),
        CategoryDefinition("学習", "勉強・解説・チュートリアルなど、知識を身につけるための内容"),
        CategoryDefinition("仕事", "業務・ビジネス・キャリアに関する内容"),
        CategoryDefinition("趣味", "ゲーム・スポーツ・旅行・音楽など、楽しみのための内容"),
        CategoryDefinition("買い物", "商品の情報や、欲しいものの記録"),
        CategoryDefinition("エンタメ", "動画・音楽・映画・番組などの娯楽"),
        CategoryDefinition("論文", "学術論文・研究報告"),
        CategoryDefinition("マニュアル", "製品や手続きの使い方・手順書"),
        CategoryDefinition("書類", "契約書・申込書・議事録などの書類"),
        CategoryDefinition("資料", "調べものや参考にするための資料・スライド"),
        CategoryDefinition("写真", "写真・画像として保存したもの"),
        CategoryDefinition("スクショ", "画面を撮影したスクリーンショット"),
        CategoryDefinition("レシート", "領収書・レシート・明細"),
        CategoryDefinition("アイデア", "思いつきやメモ書き"),
        CategoryDefinition("ToDo", "やることのリスト・タスク"),
        CategoryDefinition("その他", "上のどれにも当てはまらない内容"),
        CategoryDefinition(UNCATEGORIZED, "AI がまだ分類していない、または分類できなかった内容")
    )

    /** AI が自由に付けた分類(標準でもユーザーが作ったものでもない)の、定型の説明。 */
    const val AI_OTHER_DESCRIPTION = "AI が内容から付けた分類です。自分のカテゴリとして作ると、説明を付けて AI の分類に使えます"

    /** 「カテゴリ」とは何か、どう決まるか(カテゴリの画面とブックマークの詳細で出す説明)。 */
    val ABOUT_CATEGORY: List<String> = listOf(
        "カテゴリは、ブックマークを1つの大きな分類にまとめるラベルです。1つのブックマークに付くのは1つだけで、複数付けられるタグとは別のものです。",
        "AI が、内容を読んで標準のカテゴリや自分のカテゴリから1つ選びます。自分のカテゴリを作ると、付けた説明を AI が読んで、内容に合うものを選びます。",
        "ブックマークごとに自分でカテゴリを選ぶこともできます。自分で選んだカテゴリは、AI の再処理でも変わりません。"
    )

    /** 標準のカテゴリの説明。標準でなければ null。 */
    fun builtInDescription(name: String): String? {
        val key = key(name)
        return BUILT_IN.firstOrNull { key(it.name) == key }?.description
    }
}
