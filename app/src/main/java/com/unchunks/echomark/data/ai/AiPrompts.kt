package com.unchunks.echomark.data.ai

import com.unchunks.echomark.domain.bookmark.model.ContentKind

/**
 * ローカル LLM とクラウド API で共通のプロンプト文言。
 * 注意: 本文を埋め込むため trimIndent は使わず、行の連結で組み立てる。
 */
object AiPrompts {

    /**
     * 要約・タグ・カテゴリの指示(JSON のみを出力させる)。
     * 中身の種類([kind])ごとに、要約の主役(記事の主張・動画で話している内容・画像に写っているものなど)と、無視すべきノイズを伝える。
     * [existingTags](優先する順)を渡すと、似たタグを増やさないよう、合うものはそのまま使わせる。
     * 渡すのは先頭から [maxExistingTags] 個・合計 [maxExistingTagChars] 文字まで(長すぎるタグ名は飛ばす)。
     * @param summaryMaxChars 要約の文字数の上限。長い動画・文書などは [summaryMaxChars] で長めにする
     */
    fun analyzeInstructions(
        kind: ContentKind,
        existingTags: List<String>,
        summaryMaxChars: Int = DEFAULT_SUMMARY_CHARS,
        maxExistingTags: Int = API_MAX_EXISTING_TAGS,
        maxExistingTagChars: Int = API_MAX_EXISTING_TAG_CHARS
    ): String {
        val tags = existingTagsForPrompt(existingTags, maxExistingTags, maxExistingTagChars)
        return buildList {
            add("あなたはブックマーク整理アシスタントです。次の保存内容(${kindLabel(kind)})を分析し、JSONのみを出力してください。")
            add("説明文やコードブロックは出力しないでください。")
            add("")
            add("出力形式:")
            add("""{"summary": "${summaryMaxChars}文字以内の日本語の要約", "tags": ["タグ1", "タグ2"], "category": "カテゴリ名1つ"}""")
            add("")
            add("要約の方針:")
            addAll(kindGuidance(kind))
            add("- 本文に書かれていないことを推測で補わない。本文が無いときは、タイトルや添付から分かる範囲で書く。")
            add("- 本文が「部分ごとの要約メモ」のときは、全体を通した主題と要点をまとめる。")
            add("")
            add("本文の見出し:")
            addAll(inputHeadingGuide(kind))
            add("")
            add("ルール:")
            add("- summary は日本語で簡潔に。")
            add("- tags は内容を表す短い単語を1〜${MAX_TAGS}個。")
            if (tags.isNotEmpty()) {
                add("- tags は、下の「既存のタグ」に内容に合うものがあれば、表記を変えずにそのまま使ってください。")
                add("- 既存のタグに合うものが無いときだけ、新しいタグを作ってください(同じ意味の言い換えや表記ゆれは作らない)。")
            }
            add("- category は${categoryExamples(kind).joinToString("") { "「$it」" }}のように短い1語。")
            if (tags.isNotEmpty()) {
                add("")
                add("既存のタグ:")
                add(tags.joinToString(prefix = "[", postfix = "]", separator = ", ") { quote(it) })
            }
        }.joinToString("\n")
    }

    /** 種類の呼び名(プロンプト用)。 */
    internal fun kindLabel(kind: ContentKind): String = when (kind) {
        ContentKind.WEB_PAGE -> "Web ページ"
        ContentKind.VIDEO -> "動画"
        ContentKind.MEMO -> "メモ"
        ContentKind.IMAGE -> "画像"
        ContentKind.DOCUMENT -> "文書"
        ContentKind.AUDIO -> "音声"
    }

    /** 種類ごとの、要約の主役と無視すべきもの。 */
    private fun kindGuidance(kind: ContentKind): List<String> = when (kind) {
        ContentKind.WEB_PAGE -> listOf(
            "- 記事の主張・結論と、それを支える要点をまとめる。",
            "- 広告・ナビゲーション・関連記事・Cookie やログインの案内など、ページの部品に由来する文は無視する。"
        )
        ContentKind.VIDEO -> listOf(
            "- 動画で話している内容(字幕・文字起こし)を主役にし、「何についての動画か」と要点をまとめる。",
            "- 説明文の宣伝・リンク・SNS の告知・スポンサーの紹介・チャンネル登録のお願いは無視する。",
            "- 字幕が無いときは、タイトルと説明文から分かる範囲で書く。"
        )
        ContentKind.MEMO -> listOf(
            "- 短いメモは言い換えたり膨らませたりせず、書かれている要点をそのまま使う。",
            "- ToDo・買い物リスト・アイデアなどは、その性質が分かるように書く(例: 「旅行の準備の ToDo: …」)。"
        )
        ContentKind.IMAGE -> listOf(
            "- 何の画像か(写真・スクリーンショット・レシート・資料・図表など)を最初に示す。写っているものと画像内の文字から判断する。",
            "- 画像内の文字や数値のうち重要なもの(店名・金額・日付・見出し・エラーメッセージなど)を含める。",
            "- 写っている人物が誰かは推測しない。"
        )
        ContentKind.DOCUMENT -> listOf(
            "- 文書の種類(論文・マニュアル・請求書・契約書・スライドなど)と目的を示し、要点をまとめる。",
            "- 結論・金額・期限など、重要な数値や日付は残す。"
        )
        ContentKind.AUDIO -> listOf(
            "- 話の主題と要点をまとめる。複数の話者による議論・会議なら、結論や決まったことを優先する。",
            "- 文字起こしには聞き間違いが含まれることがある。文脈から自然に解釈する。"
        )
    }

    /** 本文に入りうる見出し(本文の取得・取り出しで付けるもの)の説明。 */
    private fun inputHeadingGuide(kind: ContentKind): List<String> = buildList {
        if (kind == ContentKind.VIDEO) {
            add("- 「字幕:」「字幕(自動生成):」の後は動画の字幕。自動生成の字幕は誤認識や句読点の欠落を含む。")
            add("- 「チャンネル:」は投稿者、「キーワード:」は投稿者が付けた検索用の語。")
        }
        add("- 「--- 読み取った文字 ---」のような「--- 〜 ---」の行は、ファイルから取り出した部分(画像内の文字・PDF のテキスト・音声の文字起こしなど)の見出し。読み取りの誤りを含むことがある。")
        add("- 「[部分 1/3]」のような見出しは、長い本文を分けて要約したメモの区切り。")
    }

    /** category の例。種類に合う候補を先に出す。 */
    internal fun categoryExamples(kind: ContentKind): List<String> = when (kind) {
        ContentKind.IMAGE -> listOf("写真", "スクショ", "レシート", "資料")
        ContentKind.DOCUMENT -> listOf("論文", "マニュアル", "書類", "資料")
        ContentKind.MEMO -> listOf("アイデア", "ToDo")
        ContentKind.VIDEO, ContentKind.AUDIO -> listOf("エンタメ")
        ContentKind.WEB_PAGE -> emptyList()
    } + BASE_CATEGORIES

    private val BASE_CATEGORIES = listOf("技術", "ニュース", "レシピ", "学習", "仕事", "趣味", "その他")

    /**
     * 要約の文字数の上限。長い動画・文書・音声は話題が多いため長めにし、短いメモ・画像は短く保つ。
     * @param isLong 本文が長い(分割して要約した)か、長い中身のファイル(PDF・音声・動画)をそのまま渡すとき true
     */
    fun summaryMaxChars(kind: ContentKind, isLong: Boolean): Int = when {
        !isLong -> DEFAULT_SUMMARY_CHARS
        kind == ContentKind.VIDEO || kind == ContentKind.DOCUMENT || kind == ContentKind.AUDIO -> LONG_SUMMARY_CHARS
        kind == ContentKind.WEB_PAGE -> MEDIUM_SUMMARY_CHARS
        else -> DEFAULT_SUMMARY_CHARS
    }

    const val DEFAULT_SUMMARY_CHARS = 100

    /** これより長い本文は「長い」とみなし、要約を長めにする */
    const val LONG_TEXT_CHARS = 6_000
    private const val MEDIUM_SUMMARY_CHARS = 150
    private const val LONG_SUMMARY_CHARS = 200

    /** AI に付けさせるタグの最大数。多いほど似たタグが増えるため少なめにする */
    const val MAX_TAGS = 3

    /** クラウド API に渡す既存タグの上限(個数・合計文字数) */
    const val API_MAX_EXISTING_TAGS = 50
    const val API_MAX_EXISTING_TAG_CHARS = 1_000

    /** ローカル LLM に渡す既存タグの上限。文脈長(入力と出力の合計)が小さいため少なめにする */
    const val LOCAL_MAX_EXISTING_TAGS = 20
    const val LOCAL_MAX_EXISTING_TAG_CHARS = 300

    /** これより長いタグ名はプロンプトに入れない(予算を食うだけで、使い回されることもまず無い) */
    private const val MAX_PROMPT_TAG_NAME_CHARS = 30

    /** プロンプトに入れる既存タグを、先頭から個数と合計文字数の予算に収まる分だけ選ぶ。 */
    internal fun existingTagsForPrompt(existingTags: List<String>, maxCount: Int, maxChars: Int): List<String> {
        val selected = mutableListOf<String>()
        var chars = 0
        for (raw in existingTags) {
            if (selected.size >= maxCount) break
            val name = raw.trim()
            if (name.isEmpty() || name.length > MAX_PROMPT_TAG_NAME_CHARS) continue
            // 引用符と区切り(", ")の分も数える
            val cost = name.length + 4
            if (chars + cost > maxChars) break
            selected += name
            chars += cost
        }
        return selected
    }

    /** JSON の文字列として引用する(タグ名の " や \ で配列の形が崩れないように)。 */
    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * 分析対象の本文部分。
     * @param body 本文(長い本文は分割して要約したメモ)。[maxChars] で切り詰める
     * @param attachmentLabel 本文と一緒に渡すファイルの呼び名(「画像」など)。渡さなければ null
     */
    fun analyzeInput(title: String, body: String, maxChars: Int, attachmentLabel: String? = null): String = buildList {
        if (title.isNotBlank()) add("タイトル: ${title.trim()}")
        if (attachmentLabel != null) add("添付: 保存した${attachmentLabel}のファイルそのもの。本文と合わせて内容を判断してください。")
        add("保存内容:")
        add(body.take(maxChars).ifBlank { "(本文なし)" })
    }.joinToString("\n")

    /**
     * 長い本文の一部を要約させる指示(分割要約の map 側)。後でまとめるためのメモを JSON で出力させる。
     * JSON にするのは、ローカル LLM で JSON が閉じた時点で生成を打ち切れるようにするため(繰り返しに陥っても止まる)。
     */
    fun partInstructions(kind: ContentKind, noteMaxChars: Int): String = buildList {
        add("あなたは長い保存内容(${kindLabel(kind)})を読むアシスタントです。次の本文は全体の一部です。")
        add("この部分の要点を、後で全体の要約を作るためのメモとして書き出し、JSONのみを出力してください。")
        add("説明文やコードブロックは出力しないでください。")
        add("")
        add("出力形式:")
        add("""{"notes": "この部分の要点(${noteMaxChars}文字以内の日本語)"}""")
        add("")
        add("方針:")
        addAll(kindGuidance(kind))
        add("- 固有名詞・数値・結論は残す。本文に書かれていないことは書かない。")
        add("- この部分に要点が無ければ notes は空文字にする。")
    }.joinToString("\n")

    /** 部分要約の入力。 */
    fun partInput(title: String, part: TextPart): String = buildList {
        if (title.isNotBlank()) add("タイトル: ${title.trim()}")
        add("[部分 ${part.number}/${part.total}]")
        add(part.text)
    }.joinToString("\n")

    /**
     * 部分ごとの要約メモを、最終の要約に渡す本文にまとめる。
     * @param notes (部分の番号, メモ) の番号順のリスト
     * @param totalParts 本文を分けた部分の数(間引いて要約しなかった部分も含む)
     */
    fun digestBody(notes: List<Pair<Int, String>>, totalParts: Int): String = buildList {
        val omitted = totalParts - notes.size
        add(
            "長い本文を${totalParts}個の部分に分けて要約したメモです" +
                if (omitted > 0) "(長さと時間の都合で${omitted}個の部分は省略)。" else "。"
        )
        notes.forEach { (number, note) ->
            add("")
            add("[部分 $number/$totalParts]")
            add(note)
        }
    }.joinToString("\n")

    /**
     * RAG チャットの指示。context は呼び出し側で "[n] タイトル: 要約" に整形済み
     * (番号は呼び出し側の付番をそのまま使う)。
     */
    fun chatInstructions(context: List<String>): String = buildList {
        add("あなたはユーザーの保存したブックマークに答えるアシスタントです。")
        if (context.isEmpty()) {
            add("関連する保存内容は見つかりませんでした。その旨を伝えたうえで、分かる範囲で簡潔に日本語で答えてください。")
        } else {
            add("以下の保存内容だけを根拠に、質問へ日本語で簡潔に答えてください。")
            add("根拠がない場合は、分からないと答えてください。参照した番号を [1] のように示してください。")
            add("")
            add("保存内容:")
            context.forEach { add(it); add("") }
        }
    }.joinToString("\n").trimEnd()

    /** 接続テスト用の短い依頼。 */
    const val CONNECTION_TEST_MESSAGE = "接続テストです。「OK」とだけ返してください。"
}
