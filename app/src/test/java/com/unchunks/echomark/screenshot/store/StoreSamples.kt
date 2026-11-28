package com.unchunks.echomark.screenshot.store

import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.ProcessingInfo
import com.unchunks.echomark.domain.bookmark.model.ProcessingStage
import com.unchunks.echomark.domain.bookmark.model.Recommendation
import com.unchunks.echomark.domain.bookmark.model.RecommendationReason
import com.unchunks.echomark.domain.bookmark.model.SearchMatch
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.Tag
import com.unchunks.echomark.ui.components.PreviewSamples

/**
 * Play ストアの掲載画像・紹介動画に使うサンプルデータ。
 *
 * 技術記事に偏ったテスト用のサンプル([PreviewSamples])とは別に、だれにでも身近な話題(暮らし・料理・旅行・仕事・健康)を
 * そろえている。サイト名・URL は実在しないもの(example.jp)にして、他社の名前や商標が写り込まないようにしている。
 * 相対日時が撮る日で変わらないよう、基準時刻は [PreviewSamples.NOW] に合わせる。
 */
internal object StoreSamples {
    const val NOW: Long = PreviewSamples.NOW
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /** 保存した直後で、AI が本文を取り込んでいるところ(一覧の先頭に出し、自動で整理されることを見せる) */
    val justSaved = Bookmark(
        id = 100,
        type = BookmarkType.URL,
        contentUri = "https://camera.example.jp/autumn-leaves-photo",
        title = "https://camera.example.jp/autumn-leaves-photo",
        createdAt = NOW - 1 * MINUTE,
        lastAccessedAt = NOW - 1 * MINUTE,
        aiStatus = AiStatus.PROCESSING,
        processing = ProcessingInfo(stage = ProcessingStage.ANALYZE)
    )

    val sleep = Bookmark(
        id = 1,
        type = BookmarkType.URL,
        contentUri = "https://health.example.jp/articles/sleep-habits",
        title = "眠りの質を上げる 5 つの習慣 ― 寝る前の 1 時間の過ごし方",
        summary = "寝る 1 時間前にはスマホを置く、入浴は就寝の 90 分前、朝は光を浴びる など、" +
            "今夜から試せる 5 つの習慣を、理由とあわせて紹介している。",
        category = "健康",
        createdAt = NOW - 2 * HOUR,
        lastAccessedAt = NOW - 2 * HOUR,
        aiStatus = AiStatus.DONE,
        tags = listOf("健康", "睡眠", "習慣"),
        aiTags = setOf("健康", "睡眠", "習慣"),
        imageUrl = "https://health.example.jp/og/sleep.png",
        siteName = "からだラボ",
        estimatedReadingMinutes = 4
    )

    val kyoto = Bookmark(
        id = 2,
        type = BookmarkType.URL,
        contentUri = "https://travel.example.jp/kyoto/autumn-hidden-spots",
        title = "京都・紅葉の穴場 10 選 ― 混雑を避けて楽しむ秋の散策ルート",
        summary = "人の多い名所を避けて楽しめる紅葉の穴場を、見頃の時期・アクセス・空いている時間帯とともにまとめた記事。" +
            "朝 8 時前の拝観と、電車での回り方をすすめている。",
        content = "京都の紅葉は 11 月中旬から下旬が見頃です。人気の寺院は昼前から混み合うため、朝 8 時前の拝観がおすすめ。" +
            "この記事では、大原・鷹峯・山科の 3 つのエリアから、ゆっくり紅葉を楽しめる 10 か所を紹介します。" +
            "どのエリアもバスより電車の乗り継ぎの方が早く着きます。",
        category = "旅行",
        createdAt = NOW - 1 * DAY,
        lastAccessedAt = NOW - 1 * DAY,
        aiStatus = AiStatus.DONE,
        tags = listOf("旅行", "京都", "紅葉"),
        aiTags = setOf("京都", "紅葉"),
        imageUrl = "https://travel.example.jp/og/kyoto.png",
        siteName = "たびメモ",
        isFavorite = true,
        estimatedReadingMinutes = 6
    )

    val mealPrep = Bookmark(
        id = 3,
        type = BookmarkType.URL,
        contentUri = "https://recipe.example.jp/meal-prep/weekly",
        title = "作り置きで平日がラクになる、1 週間の献立と買い物リスト",
        summary = "日曜に 90 分で 5 品を作り置きし、平日は温めて組み合わせるだけにする献立。買い物リストと日持ちの目安つき。",
        category = "料理",
        createdAt = NOW - 3 * DAY,
        lastAccessedAt = NOW - 3 * DAY,
        aiStatus = AiStatus.DONE,
        tags = listOf("料理", "作り置き", "献立"),
        aiTags = setOf("作り置き", "献立"),
        imageUrl = "https://recipe.example.jp/og/meal-prep.png",
        siteName = "まいにちレシピ帳",
        estimatedReadingMinutes = 5
    )

    val focus = Bookmark(
        id = 4,
        type = BookmarkType.URL,
        contentUri = "https://work.example.jp/pomodoro",
        title = "集中が続く「25 分」の働き方 ― ポモドーロ・テクニック入門",
        summary = "25 分の作業と 5 分の休憩をくり返すポモドーロ・テクニックの始め方と、続けるための工夫を紹介している。",
        category = "仕事",
        createdAt = NOW - 5 * DAY,
        lastAccessedAt = NOW - 5 * DAY,
        aiStatus = AiStatus.DONE,
        tags = listOf("仕事術", "集中", "習慣"),
        aiTags = setOf("仕事術", "集中"),
        imageUrl = "https://work.example.jp/og/pomodoro.png",
        siteName = "ワークスタイル研究所",
        estimatedReadingMinutes = 3
    )

    val kyotoMemo = Bookmark(
        id = 5,
        type = BookmarkType.TEXT,
        title = "京都で行きたいお店メモ",
        content = "2 日目の昼: 南禅寺の近くの湯豆腐のお店(予約がいるか確認)\n帰りに: 錦市場でお漬物",
        summary = "京都旅行で寄りたいお店のメモ。2 日目の昼は南禅寺の近くの湯豆腐、帰りに錦市場でお漬物を買う予定。",
        category = "旅行",
        createdAt = NOW - 6 * DAY,
        lastAccessedAt = NOW - 6 * DAY,
        aiStatus = AiStatus.DONE,
        tags = listOf("旅行", "京都", "グルメ"),
        aiTags = setOf("グルメ")
    )

    val ai = Bookmark(
        id = 6,
        type = BookmarkType.URL,
        contentUri = "https://tech.example.jp/how-generative-ai-works",
        title = "生成 AI はどうやって文章を作るのか ― 仕組みをやさしく解説",
        summary = "生成 AI が次の言葉を予測して文章を組み立てる仕組みを、たとえ話と図でやさしく説明した入門記事。",
        category = "テクノロジー",
        createdAt = NOW - 8 * DAY,
        lastAccessedAt = NOW - 8 * DAY,
        aiStatus = AiStatus.DONE,
        tags = listOf("AI", "テクノロジー"),
        aiTags = setOf("AI", "テクノロジー"),
        imageUrl = "https://tech.example.jp/og/ai.png",
        siteName = "テクノロジーやさしく解説",
        estimatedReadingMinutes = 8
    )

    val movingPdf = Bookmark(
        id = 7,
        type = BookmarkType.PDF,
        title = "引っ越しの手続きチェックリスト.pdf",
        summary = "転出届・郵便の転送・電気やガスの手続きなど、引っ越しの前後にやることを時期ごとに並べたチェックリスト。",
        category = "暮らし",
        createdAt = NOW - 12 * DAY,
        lastAccessedAt = NOW - 12 * DAY,
        aiStatus = AiStatus.DONE,
        tags = listOf("暮らし", "手続き"),
        aiTags = setOf("暮らし", "手続き"),
        fileName = "引っ越しの手続きチェックリスト.pdf",
        mimeType = "application/pdf",
        fileSize = 820_000
    )

    val stretch = Bookmark(
        id = 8,
        type = BookmarkType.URL,
        contentUri = "https://health.example.jp/articles/night-stretch",
        title = "寝る前 5 分のストレッチで、体をゆるめる",
        summary = "布団の上でできる 5 分のストレッチ。首・肩・腰をゆっくり伸ばして体の緊張をほどき、寝つきをよくする。",
        category = "健康",
        createdAt = NOW - 20 * DAY,
        lastAccessedAt = NOW - 20 * DAY,
        aiStatus = AiStatus.DONE,
        tags = listOf("健康", "ストレッチ"),
        aiTags = setOf("ストレッチ"),
        imageUrl = "https://health.example.jp/og/stretch.png",
        siteName = "からだラボ",
        estimatedReadingMinutes = 2
    )

    val habitBook = Bookmark(
        id = 9,
        type = BookmarkType.TEXT,
        title = "読書メモ: 習慣は「きっかけ・行動・ごほうび」",
        content = "習慣はきっかけ・行動・ごほうびの 3 つでできている。夜ふかしをやめたいなら、きっかけ(寝る前のスマホ)を変える。",
        summary = "習慣は「きっかけ・行動・ごほうび」でできているという本のメモ。夜ふかしをやめるには、寝る前のスマホというきっかけを変える。",
        category = "読書",
        createdAt = NOW - 45 * DAY,
        lastAccessedAt = NOW - 40 * DAY,
        aiStatus = AiStatus.DONE,
        tags = listOf("読書", "習慣"),
        aiTags = setOf("習慣")
    )

    val garden = Bookmark(
        id = 10,
        type = BookmarkType.URL,
        contentUri = "https://life.example.jp/balcony-garden",
        title = "ベランダではじめる家庭菜園 ― 秋に植えたい野菜 6 種",
        summary = "プランター 1 つから始められる秋植えの野菜を、育てやすさ・収穫までの日数とともに紹介している。",
        category = "暮らし",
        createdAt = NOW - 75 * DAY,
        lastAccessedAt = NOW - 60 * DAY,
        aiStatus = AiStatus.DONE,
        tags = listOf("暮らし", "家庭菜園"),
        aiTags = setOf("家庭菜園"),
        imageUrl = "https://life.example.jp/og/garden.png",
        siteName = "くらしの小さな工夫"
    )

    /** ホームの一覧に並べる順(新しい順) */
    val library: List<Bookmark> = listOf(justSaved, sleep, kyoto, mealPrep, focus, kyotoMemo, ai, movingPdf, stretch)

    val tags = listOf(
        Tag(1, "旅行"), Tag(2, "健康"), Tag(3, "料理"), Tag(4, "習慣"), Tag(5, "京都"), Tag(6, "仕事術"), Tag(7, "読書")
    )

    val categories = listOf("健康", "旅行", "料理", "仕事", "暮らし", "テクノロジー", "読書")

    /** 「今日の再発見」(しばらく開いていないもの) */
    val rediscover = listOf(garden, habitBook)

    /** 「次に読む」のおすすめ */
    val recommendations = listOf(
        Recommendation(focus, RecommendationReason.Short(3)),
        Recommendation(ai, RecommendationReason.SharedTag("習慣"))
    )

    // ---- 意味で探す ----

    /** キーワードには一致しないが、意味の近さで見つかる検索語 */
    const val SEARCH_QUERY = "ぐっすり眠りたい"

    val searchResults = listOf(sleep, stretch, habitBook)

    /** どれも「ぐっすり眠りたい」という語は含まないので、意味の近さだけで見つかったものとして出す */
    val searchMatches: Map<Long, SearchMatch> = searchResults.associate { it.id to SearchMatch() }

    // ---- チャット ----

    val chatQuestion = ChatMessage(
        id = 1, conversationId = 1, role = ChatRole.USER,
        content = "京都旅行の前に、保存した記事とメモの要点をまとめて", createdAt = NOW - 3 * MINUTE
    )

    const val CHAT_ANSWER = """## 京都旅行のポイント
保存した記事とメモから、次の 3 つが役に立ちそうです。

1. **紅葉の見頃は 11 月中旬〜下旬**。朝 8 時前なら人気の寺院も空いています [1]
2. 穴場は **大原・鷹峯・山科** の 3 エリア。バスより電車の乗り継ぎが早く着きます [1]
3. 2 日目の昼は、メモにあった **南禅寺の近くの湯豆腐** がルートの途中です [2]"""

    val chatAnswer = ChatMessage(
        id = 2, conversationId = 1, role = ChatRole.ASSISTANT,
        content = CHAT_ANSWER,
        referencedBookmarkIds = listOf(kyoto.id, kyotoMemo.id),
        createdAt = NOW - 2 * MINUTE
    )

    val chatReferenced = mapOf(kyoto.id to kyoto, kyotoMemo.id to kyotoMemo)

    // ---- ナレッジグラフ ----

    private class GraphTopic(val tag: String, val subTags: List<String>, val titles: List<String>)

    // 図の点に出る名前なので、短いタイトルにしている
    private val graphTopics = listOf(
        GraphTopic(
            "旅行", listOf("京都", "紅葉", "グルメ"),
            listOf("京都・紅葉の穴場", "京都のお店メモ", "紅葉の撮り方", "週末の温泉宿", "旅の持ち物リスト", "新幹線のコツ")
        ),
        GraphTopic(
            "健康", listOf("睡眠", "ストレッチ", "習慣"),
            listOf("眠りの質を上げる習慣", "寝る前のストレッチ", "朝の白湯の効果", "肩こり体操", "1 日 8000 歩", "姿勢のよい座り方")
        ),
        GraphTopic(
            "料理", listOf("作り置き", "献立", "和食"),
            listOf("1 週間の作り置き献立", "15 分の副菜", "だしの取り方", "冷凍のコツ", "野菜のスープ", "お弁当のおかず")
        ),
        GraphTopic(
            "仕事術", listOf("集中", "習慣", "メモ術"),
            listOf("25 分の働き方", "会議メモのコツ", "メールの片づけ方", "タスクは 3 つに", "朝の 30 分計画", "読書メモの書き方")
        ),
        GraphTopic(
            "暮らし", listOf("家庭菜園", "手続き", "片づけ"),
            listOf("ベランダ菜園", "引っ越しの手続き", "収納の 3 ルール", "電気代を下げる", "防災グッズ", "衣替えの段取り")
        )
    )

    /** 話題ごとにまとまり、「習慣」で健康と仕事術がつながるように並べる */
    val graphBookmarks: List<Bookmark> = (1..30).map { i ->
        val topic = graphTopics[i % graphTopics.size]
        Bookmark(
            id = 1_000L + i, type = BookmarkType.URL, title = topic.titles[(i / graphTopics.size) % topic.titles.size],
            createdAt = NOW - i * DAY, lastAccessedAt = NOW - i * DAY,
            tags = listOf(topic.tag, topic.subTags[i % topic.subTags.size])
        )
    }
}
