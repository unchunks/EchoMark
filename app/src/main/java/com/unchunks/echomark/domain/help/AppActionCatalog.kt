package com.unchunks.echomark.domain.help

import com.unchunks.echomark.domain.model.SummaryLanguage
import com.unchunks.echomark.domain.repository.DesignStyle
import com.unchunks.echomark.domain.repository.ThemeMode

/**
 * AI に示す1つの操作。AI は回答の最後に「操作: [id]」(値の要るものは「操作: [id] 値」)と書いて提案する。
 * @property id AI が書く名前。小型モデルが写しやすいよう、英小文字と _ だけにする
 * @property description AI に示す説明(短く)
 * @property phrases 質問にこの言葉があれば、AI が提案しなくても候補に出す(小型モデルが書き忘れても操作できるように)
 * @property takesValue 値(URL・検索語・メモの本文)が要るか
 */
class AppActionSpec(
    val id: String,
    val description: String,
    val phrases: List<String> = emptyList(),
    val takesValue: Boolean = false,
    private val create: (value: String?) -> AppAction?
) {
    /** AI に示す1行 */
    val promptLine: String get() = if (takesValue) "- $id 値: $description" else "- $id: $description"

    /** 値から操作を作る。値が要るのに無い・正しくなければ null */
    fun actionFor(value: String?): AppAction? = create(value?.trim()?.takeIf { it.isNotEmpty() })
}

/**
 * 使い方の AI から行える操作の一覧と、AI の回答からの取り出し。Android に依存しない純粋な処理。
 *
 * 端末内 AI の文脈は短いため、全部の操作は示さない。質問に近い案内の節に結び付けた操作([GuideSection.actions])と、
 * どの質問でも使う操作([ALWAYS])だけを示す([candidatesFor])。
 */
object AppActionCatalog {

    /** 1回の質問で AI に示す操作の数の上限(プロンプトの文字数を抑える) */
    private const val MAX_CANDIDATES = 10

    /** 1つの回答に出す操作のボタンの数の上限 */
    const val MAX_SUGGESTIONS = 3

    private val URL_PATTERN = Regex("""https?://[^\s<>"'「」]+""")

    /** 回答の中の操作の行。「操作:」の後ろに名前と値。行頭の記号・強調(- * `)は許す */
    private val ACTION_LINE = Regex("""^[\s\-*`>]*操作\s*[:：]\s*`?([a-z_]+)`?\s*(.*)$""")

    /** 操作の行の書きかけ(ストリーミングの途中の最後の行)。「操」「操作」「操作: …」 */
    private val ACTION_LINE_PREFIX = Regex("""^[\s\-*`>]*操(作(\s*[:：].*)?)?$""")

    private fun open(id: String, screen: AppScreen, description: String, vararg phrases: String) =
        AppActionSpec(id, description, phrases.toList()) { AppAction.Open(screen) }

    private fun simple(id: String, action: AppAction, description: String, vararg phrases: String) =
        AppActionSpec(id, description, phrases.toList()) { action }

    val specs: List<AppActionSpec> = listOf(
        AppActionSpec("save_link", "URL のページをブックマークに保存", takesValue = true) { value ->
            value?.let { URL_PATTERN.find(it)?.value }?.let(AppAction::SaveLink)
        },
        AppActionSpec("save_note", "本文をメモとして保存", takesValue = true) { value -> value?.let(AppAction::SaveNote) },
        AppActionSpec("search", "ブックマークを検索", takesValue = true) { value -> value?.let(AppAction::Search) },
        open("open_add", AppScreen.ADD_BOOKMARK, "追加の画面(リンク・メモ・ファイル)を開く", "ファイルを追加", "ファイルを保存"),
        open("open_bookmarks", AppScreen.BOOKMARKS, "ブックマークの一覧を開く", "一覧を開"),
        open("open_unread", AppScreen.UNREAD, "未読だけの一覧を開く", "未読"),
        open("open_chat", AppScreen.NEW_CHAT, "新しいチャットを開く", "チャットを開", "チャットを始め"),
        open("open_explore", AppScreen.EXPLORE, "「見つける」タブを開く"),
        open("open_settings", AppScreen.SETTINGS, "設定を開く(バックアップ・ロック・通知・取り込み・購読・削除もここ)", "設定を開"),
        open("open_ai_settings", AppScreen.AI_SETTINGS, "AI の設定(モデルのダウンロード)を開く", "AI の設定", "AI設定", "モデル"),
        open("open_categories", AppScreen.CATEGORIES, "カテゴリの画面を開く", "カテゴリを"),
        open("open_queue", AppScreen.PROCESSING_QUEUE, "処理の状況を開く", "処理の状況", "進み具合"),
        open("open_offline_videos", AppScreen.OFFLINE_VIDEOS, "オフラインの動画を開く", "オフラインの動画"),
        open("open_onboarding", AppScreen.ONBOARDING, "はじめにの案内を開く", "はじめにの案内"),
        open("open_review", AppScreen.REVIEW, "読む・ふり返るを開く", "ふり返", "復習", "あとで読む"),
        open("open_discover", AppScreen.DISCOVER, "資料を探すを開く", "資料を探"),
        open("open_insights", AppScreen.INSIGHTS, "ライブラリの分析を開く", "分析", "統計"),
        open("open_graph", AppScreen.GRAPH, "ナレッジグラフを開く", "グラフ", "つながり"),
        open("open_places", AppScreen.PLACES, "場所の地図を開く", "地図"),
        open("open_collections", AppScreen.COLLECTIONS, "コレクションを開く", "コレクション"),
        open("open_tags", AppScreen.TAGS, "タグの管理を開く", "タグの管理", "タグを整理"),
        open("open_duplicates", AppScreen.DUPLICATES, "重複と関連を開く", "重複"),
        open("open_broken_links", AppScreen.BROKEN_LINKS, "リンク切れを開く", "リンク切れ"),
        open("open_podcasts", AppScreen.PODCASTS, "ポッドキャストを開く", "ポッドキャスト"),
        open("open_reading_dictionary", AppScreen.READING_DICTIONARY, "読みの辞書を開く", "読みの辞書", "読み方"),
        simple("theme_dark", AppAction.SetTheme(ThemeMode.DARK), "テーマをダークにする", "ダーク", "暗く"),
        simple("theme_light", AppAction.SetTheme(ThemeMode.LIGHT), "テーマをライトにする", "ライト", "明るく"),
        simple("theme_system", AppAction.SetTheme(ThemeMode.SYSTEM), "テーマを端末の設定に合わせる"),
        simple("design_classic", AppAction.SetDesign(DesignStyle.CLASSIC), "デザインをクラシックにする", "クラシック"),
        simple("design_echo", AppAction.SetDesign(DesignStyle.ECHO), "デザインをエコーにする", "エコー"),
        simple("design_paper", AppAction.SetDesign(DesignStyle.PAPER), "デザインをペーパーにする", "ペーパー"),
        simple("design_clear", AppAction.SetDesign(DesignStyle.CLEAR), "デザインをクリアにする", "クリア", "iPhone", "アイフォン"),
        simple("design_soft", AppAction.SetDesign(DesignStyle.SOFT), "デザインをソフトにする", "ソフト", "ニューモフィズム"),
        simple("offline_on", AppAction.SetOfflineMode(true), "オフラインモードをオンにする", "オフラインにして", "通信しない"),
        simple("offline_off", AppAction.SetOfflineMode(false), "オフラインモードをオフにする", "オフラインをやめ", "オフラインを解除"),
        simple(
            "charging_only_on", AppAction.SetProcessingOption(ProcessingOption.CHARGING_ONLY, true),
            "充電中だけ処理するをオンにする", "充電中だけ"
        ),
        simple("charging_only_off", AppAction.SetProcessingOption(ProcessingOption.CHARGING_ONLY, false), "充電中だけ処理するをオフにする"),
        simple(
            "wifi_only_on", AppAction.SetProcessingOption(ProcessingOption.UNMETERED_ONLY, true),
            "Wi-Fi のときだけ通信するをオンにする", "Wi-Fi のときだけ", "wifiのときだけ"
        ),
        simple("wifi_only_off", AppAction.SetProcessingOption(ProcessingOption.UNMETERED_ONLY, false), "Wi-Fi のときだけ通信するをオフにする"),
        simple(
            "nightly_on", AppAction.SetProcessingOption(ProcessingOption.NIGHTLY_BATCH, true),
            "夜間にまとめて処理するをオンにする", "夜間に"
        ),
        simple("nightly_off", AppAction.SetProcessingOption(ProcessingOption.NIGHTLY_BATCH, false), "夜間にまとめて処理するをオフにする"),
        simple("summary_ja", AppAction.SetSummaryLanguage(SummaryLanguage.JAPANESE), "要約を日本語にする", "日本語で要約"),
        simple("summary_en", AppAction.SetSummaryLanguage(SummaryLanguage.ENGLISH), "要約を英語にする", "英語で要約", "要約を英語"),
        simple("summary_zh", AppAction.SetSummaryLanguage(SummaryLanguage.CHINESE), "要約を中国語にする", "中国語"),
        simple("summary_ko", AppAction.SetSummaryLanguage(SummaryLanguage.KOREAN), "要約を韓国語にする", "韓国語"),
        simple("reprocess_failed", AppAction.ReprocessFailed, "失敗・準備待ちの AI 処理をやり直す", "再処理", "やり直")
    )

    private val byId: Map<String, AppActionSpec> = specs.associateBy { it.id }

    /** どの質問でも示す操作(値を取るもの。案内の節に結び付けにくいため) */
    private val ALWAYS = listOf("save_link", "save_note", "search")

    fun spec(id: String): AppActionSpec? = byId[id]

    /** [sections](質問に近い案内の節)に合わせて、AI に示す操作を選ぶ */
    fun candidatesFor(sections: List<GuideSection>): List<AppActionSpec> =
        (ALWAYS + sections.flatMap { it.actions })
            .distinct()
            .mapNotNull(byId::get)
            .take(MAX_CANDIDATES)

    /**
     * 回答の本文から操作の行を除いたもの。
     * @param streaming 生成の途中か。途中なら、操作の行になりかけている最後の行(「操」「操作」)も隠す
     */
    fun visibleText(answer: String, streaming: Boolean = false): String {
        val lines = answer.lines()
        return lines
            .filterIndexed { index, line ->
                !ACTION_LINE.matches(line) && !(streaming && index == lines.lastIndex && ACTION_LINE_PREFIX.matches(line))
            }
            .joinToString("\n")
            .trimEnd()
    }

    /**
     * 回答の後に出す操作を決める。
     * 1. AI が書いた操作の行(示した [candidates] の中にあり、値が正しいものだけ)
     * 2. 質問の URL(保存を頼んでいなくても、貼られたリンクは保存できるようにする)
     * 3. 質問の言葉に当たる操作([AppActionSpec.phrases]。AI が書き忘れたとき)
     * 4. 何も無ければ、いちばん近い案内の節の最初の操作(たいていはその画面を開く)
     */
    fun suggest(
        question: String,
        answer: String,
        candidates: List<AppActionSpec>,
        sections: List<GuideSection>
    ): List<AppAction> {
        val allowed = candidates.associateBy { it.id }
        val fromAnswer = answer.lines().mapNotNull { line ->
            val match = ACTION_LINE.matchEntire(line) ?: return@mapNotNull null
            allowed[match.groupValues[1]]?.actionFor(match.groupValues[2])
        }
        val fromUrl = URL_PATTERN.findAll(question).map { AppAction.SaveLink(it.value) }.toList()
        val normalizedQuestion = normalize(question)
        val fromPhrases = candidates
            .filter { spec -> !spec.takesValue && spec.phrases.any { normalize(it) in normalizedQuestion } }
            .mapNotNull { it.actionFor(null) }
        val actions = (fromAnswer + fromUrl + fromPhrases).distinct()
        if (actions.isNotEmpty()) return actions.take(MAX_SUGGESTIONS)
        return sections.firstOrNull()?.actions?.firstNotNullOfOrNull { id -> byId[id]?.takeIf { !it.takesValue }?.actionFor(null) }
            ?.let(::listOf)
            .orEmpty()
    }

    /** 大文字・小文字と、空白・記号の違いを無くす(「Wi-Fi」と「wifi」を同じに扱う) */
    private fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }
}
