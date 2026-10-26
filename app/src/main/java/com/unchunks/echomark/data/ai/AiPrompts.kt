package com.unchunks.echomark.data.ai

import com.unchunks.components.llm.TextPart
import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.model.TagNames

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
     * @param scope 作らせる項目。要約だけ・タグとカテゴリだけのときは、出力形式とルールをその項目に絞る
     */
    fun analyzeInstructions(
        kind: ContentKind,
        existingTags: List<String>,
        summaryMaxChars: Int = DEFAULT_SUMMARY_CHARS,
        maxExistingTags: Int = API_MAX_EXISTING_TAGS,
        maxExistingTagChars: Int = API_MAX_EXISTING_TAG_CHARS,
        scope: AnalysisScope = AnalysisScope.ALL
    ): String {
        val withSummary = scope != AnalysisScope.TAGS
        val withTags = scope != AnalysisScope.SUMMARY
        val tags = if (withTags) existingTagsForPrompt(existingTags, maxExistingTags, maxExistingTagChars) else emptyList()
        val summaryField = """"summary": "${summaryMaxChars}文字以内の日本語の要約""""
        val tagFields = """"tags": ["タグ1", "タグ2"], "category": "カテゴリ名1つ""""
        return buildList {
            add("あなたはブックマーク整理アシスタントです。次の保存内容(${kindLabel(kind)})を分析し、JSONのみを出力してください。")
            add("説明文やコードブロックは出力しないでください。")
            add("")
            add("出力形式:")
            add("{" + listOfNotNull(summaryField.takeIf { withSummary }, tagFields.takeIf { withTags }).joinToString(", ") + "}")
            add("")
            add(if (withSummary) "要約の方針:" else "内容の捉え方:")
            addAll(kindGuidance(kind))
            if (withSummary) {
                add("- 本文に書かれていないことを推測で補わない。本文が無いときは、タイトルや添付から分かる範囲で書く。")
            }
            add("- 本文が「部分ごとの要約メモ」のときは、全体を通した主題と要点${if (withSummary) "をまとめる" else "から判断する"}。")
            add("")
            add("本文の見出し:")
            addAll(inputHeadingGuide(kind))
            add("")
            add("ルール:")
            if (withSummary) {
                add("- summary は日本語で簡潔に。")
                add("- summary には要約の本文だけを書く。チャンネル名・投稿者名・サイト名、動画や記事のタイトル、「この動画では」「この記事は」のような前置きは書かない。")
            }
            if (withTags) {
                add("- tags は内容を表す短い単語を1〜${MAX_TAGS}個。この内容に固有の話題・技術・製品・人物・地名などを選ぶ。")
                add("- 「動画」「記事」「まとめ」「解説」のような、どの保存にも当てはまる語や、チャンネル名・サイト名は tags にしない。")
                if (tags.isNotEmpty()) {
                    add("- tags は、下の「既存のタグ」に内容に合うものがあれば、表記を変えずにそのまま使ってください。")
                    add("- 既存のタグに合うものが無いときだけ、新しいタグを作ってください(同じ意味の言い換えや表記ゆれは作らない)。")
                }
                add("- category は${categoryExamples(kind).joinToString("") { "「$it」" }}のように短い1語。")
            }
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
            "- 動画で話している内容(字幕・文字起こし)を主役にする。最初に何を説明・主張しているかを書き、続けて具体的な要点(手順・結論・数値・おすすめなど)を書く。",
            "- 字幕が長いときは、話の流れ(導入・主な論点・結論や次に言えること)をたどって要点を選ぶ。",
            "- 自動生成の字幕の誤認識は、前後の文脈から自然に読み替える。文脈から判断できないものは書かない。",
            "- 冒頭や締めのあいさつ・自己紹介・チャンネルの紹介、説明文の宣伝・リンク・SNS の告知・スポンサーの紹介・チャンネル登録のお願いは要点にしない。",
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
            add("- 「チャプター:」は投稿者が付けた動画の章立て(話の流れの手がかり)、「キーワード:」は投稿者が付けた検索用の語。")
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

    /** 本文に出てくるかで絞り込む既存タグの、名前の最小の文字数(1文字のタグは偶然含まれやすい) */
    private const val MIN_MENTIONED_TAG_CHARS = 2

    /**
     * 既存のタグのうち、名前が [text](タイトルと本文)に出てくるもの(全角/半角・大文字/小文字は区別しない)。順番は保つ。
     * 端末内の小さいモデルは、渡した既存のタグを内容に関係なく写してしまう(「技術」「AI」のような汎用的なタグほど)ため、
     * 本文に根拠のあるタグだけを渡す。
     */
    fun existingTagsMentionedIn(existingTags: List<String>, text: String): List<String> {
        val haystack = TagNames.key(text)
        return existingTags.filter { name ->
            val key = TagNames.key(name)
            key.length >= MIN_MENTIONED_TAG_CHARS && haystack.contains(key)
        }
    }

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
     * RAG チャットの指示。context は呼び出し側で "[n] タイトル(何日前に保存): 要約" に整形済み
     * (番号は呼び出し側の付番をそのまま使う)。質問に近いものが無ければ、最近保存したものが渡される。
     * @param compact 端末内 AI 向けの短い指示にする(プロンプトの読み込みの時間を減らす)
     */
    fun chatInstructions(context: List<String>, compact: Boolean = false): String = buildList {
        if (compact) {
            // 端末内 AI 向けの短い指示。遅い端末ではプロンプトの読み込みに 100 文字あたり数秒かかるため、要点だけにする
            add("あなたは EchoMark のアシスタントです。保存したブックマークを手がかりに、質問へ日本語で簡潔に答えます。")
            add("- 保存内容を使った文には [1] のように番号を付ける。保存内容に無いことは「一般的には」と分けて答える。")
            add("- あいさつや雑談には、保存内容に触れずに自然に答える。")
        } else {
            add("あなたは EchoMark のアシスタントです。ユーザーが保存したブックマーク(記事・動画・メモ・ファイル)を手がかりに、質問へ日本語で的確に答えます。")
            add("")
            add("答え方:")
            add("- 最初に答えを1〜2文で書き、必要なときだけ箇条書きや短い見出し(Markdown)で補う。前置きや質問の繰り返しは書かない。")
            add("- 保存内容から答えた文には、その番号を [1] のように付ける。保存内容に無いことを、保存内容にあるように書かない。")
            add("- 保存内容に答えが無ければそう伝え、一般的な知識で答えられるときは「一般的には」と分けて答える。")
            add("- あいさつや雑談、保存と関係の無い質問には、保存内容に触れずに自然に答える。")
            add("- 続きの質問は前の会話を踏まえて答える。何を聞かれているか分からないときは、確認の質問を1つ返す。")
        }
        add("")
        if (context.isEmpty()) {
            add("この質問に関係する保存内容は見つかりませんでした。")
        } else {
            add("保存内容(質問に近い順。近いものが無いときは最近保存した順):")
            context.forEach { add(it); add("") }
        }
    }.joinToString("\n").trimEnd()

    /** 接続テスト用の短い依頼。 */
    const val CONNECTION_TEST_MESSAGE = "接続テストです。「OK」とだけ返してください。"
}
