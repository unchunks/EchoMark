package com.unchunks.echomark.data.ai

import com.unchunks.components.llm.TextPart
import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.bookmark.model.LimitedAccess
import com.unchunks.echomark.domain.bookmark.model.SummaryProfile
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.model.SummaryFormat
import com.unchunks.echomark.domain.model.SummaryLanguage
import com.unchunks.echomark.domain.model.SummaryStyle
import com.unchunks.echomark.domain.model.CategoryDefinition
import com.unchunks.echomark.domain.model.CategoryNames
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
     * @param profile 要約の型(論文・Q&A・レシピなど)。型に合う方針を種類ごとの方針に足す
     */
    fun analyzeInstructions(
        kind: ContentKind,
        existingTags: List<String>,
        summaryMaxChars: Int = DEFAULT_SUMMARY_CHARS,
        maxExistingTags: Int = API_MAX_EXISTING_TAGS,
        maxExistingTagChars: Int = API_MAX_EXISTING_TAG_CHARS,
        scope: AnalysisScope = AnalysisScope.ALL,
        profile: SummaryProfile = SummaryProfile.GENERAL,
        style: SummaryStyle = SummaryStyle(),
        feedback: String? = null,
        categories: List<CategoryDefinition> = emptyList()
    ): String {
        val withSummary = scope != AnalysisScope.TAGS
        val withTags = scope != AnalysisScope.SUMMARY
        val tags = if (withTags) existingTagsForPrompt(existingTags, maxExistingTags, maxExistingTagChars) else emptyList()
        val userCategories = if (withTags) categoriesForPrompt(categories, API_MAX_CATEGORIES, API_MAX_CATEGORY_DESCRIPTION_CHARS) else emptyList()
        val summaryField = """"summary": "${summaryFieldText(style, summaryMaxChars)}""""
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
            addAll(profileGuidance(profile))
            if (withSummary) {
                add("- 本文に書かれていないことを推測で補わない。本文が無いときは、タイトルや添付から分かる範囲で書く。")
                add(LIMITED_ACCESS_RULE)
                feedback?.takeIf { it.isNotBlank() }?.let { add("- 前回の要約は次の点がよくありませんでした。直して書き直してください: $it") }
            }
            add("- 本文が「部分ごとの要約メモ」のときは、全体を通した主題と要点${if (withSummary) "をまとめる" else "から判断する"}。")
            add("")
            add("本文の見出し:")
            addAll(inputHeadingGuide(kind, profile))
            add("")
            add("ルール:")
            if (withSummary) {
                addAll(summaryRules(style))
                add("- summary には要約の本文だけを書く。チャンネル名・投稿者名・サイト名、動画や記事のタイトル、「この動画では」「この記事は」のような前置きは書かない。")
            }
            if (withTags) {
                add("- tags は内容を表す短い単語を1〜${MAX_TAGS}個。この内容に固有の話題・技術・製品・人物・地名などを選ぶ。")
                add("- 「動画」「記事」「まとめ」「解説」のような、どの保存にも当てはまる語や、チャンネル名・サイト名は tags にしない。")
                if (tags.isNotEmpty()) {
                    add("- tags は、下の「既存のタグ」に内容に合うものがあれば、表記を変えずにそのまま使ってください。")
                    add("- 既存のタグに合うものが無いときだけ、新しいタグを作ってください(同じ意味の言い換えや表記ゆれは作らない)。")
                }
                if (userCategories.isEmpty()) {
                    add("- category は${categoryExamples(kind, profile).joinToString("") { "「$it」" }}のように短い1語。")
                } else {
                    add("- category は、下の「選べるカテゴリ」から内容に最も合うものを1つ選び、名前をそのまま書く(説明を参考にする)。")
                    add("- どれにも当てはまらないときだけ、${categoryExamples(kind, profile).joinToString("") { "「$it」" }}のように短い1語にする。")
                }
            }
            if (tags.isNotEmpty()) {
                add("")
                add("既存のタグ:")
                add(tags.joinToString(prefix = "[", postfix = "]", separator = ", ") { quote(it) })
            }
            if (userCategories.isNotEmpty()) {
                add("")
                add("選べるカテゴリ:")
                userCategories.forEach { add("- ${quote(it.name)}" + if (it.description.isNotEmpty()) ": ${it.description}" else "") }
            }
        }.joinToString("\n")
    }

    /**
     * 端末内 AI 向けの短い解析の指示(出力の形は [analyzeInstructions] と同じ)。
     * 小さいモデルは規則の列挙を読み取れず、カテゴリの候補の語(「技術」など)をタグやカテゴリにそのまま写しやすい。
     * 出力の例を1つ見せると、本文に固有の語をタグに選ぶようになる(例を2つにすると、例のカテゴリを関係ない保存に写すことがあった)。
     * 遅い端末ではプロンプトの読み込みに 100 文字あたり数秒かかるため、規則は要点だけにする。
     * 例のタグを写したものは、保存内容に出てこないタグを除く処理(AnalysisParser.keepMentionedTags)で落とす。
     * @param digest 本文が、長い本文を部分ごとに要約したメモのとき true
     * @param profile 要約の型。型に合う短い言い方で、要約の主役を伝える
     * @param style 要約の言語と書き方。例の要約も、言語と書き方に合わせる
     */
    fun compactAnalyzeInstructions(
        kind: ContentKind,
        existingTags: List<String>,
        summaryMaxChars: Int = DEFAULT_SUMMARY_CHARS,
        scope: AnalysisScope = AnalysisScope.ALL,
        digest: Boolean = false,
        profile: SummaryProfile = SummaryProfile.GENERAL,
        style: SummaryStyle = SummaryStyle(),
        feedback: String? = null,
        categories: List<CategoryDefinition> = emptyList()
    ): String {
        val withSummary = scope != AnalysisScope.TAGS
        val withTags = scope != AnalysisScope.SUMMARY
        val tags = if (withTags) {
            existingTagsForPrompt(existingTags, LOCAL_MAX_EXISTING_TAGS, LOCAL_MAX_EXISTING_TAG_CHARS)
        } else {
            emptyList()
        }
        val userCategories = if (withTags) categoriesForPrompt(categories, LOCAL_MAX_CATEGORIES, LOCAL_MAX_CATEGORY_DESCRIPTION_CHARS) else emptyList()
        fun output(summary: String, tagValues: String, category: String) = "{" + listOfNotNull(
            """"summary": "$summary"""".takeIf { withSummary },
            """"tags": [$tagValues], "category": "$category"""".takeIf { withTags }
        ).joinToString(", ") + "}"
        return buildList {
            add("次の保存内容(${kindLabel(kind)})を読み、JSONだけを出力してください。")
            add(output("要約", "\"語\", \"語\"", "分類"))
            if (withSummary) {
                add("- summary: ${compactGuidance(kind, profile)}。${style.language.limitPhrase(summaryMaxChars)}。${compactFormatHint(style)}本文を写さず短く言い換える。" +
                    "タイトル・サイト名・チャンネル名・前置きは書かない。")
                if (digest) add("- 本文が「[部分 1/3]」のように分かれたメモなら、全体を通してまとめる。")
                add(COMPACT_LIMITED_ACCESS_RULE)
                feedback?.takeIf { it.isNotBlank() }?.let { add("- 前回の要約の悪かった点: $it 直して書き直す。") }
            }
            if (withTags) {
                add("- tags: 本文に出てくる、この内容に固有の語(料理名・製品名・技術名・人名・地名など)を1〜${MAX_TAGS}個。")
                if (tags.isNotEmpty()) {
                    add("- tags は、次の既存のタグに合うものがあれば表記を変えずに使う: " +
                        tags.joinToString(prefix = "[", postfix = "]", separator = ", ") { quote(it) })
                }
                if (userCategories.isEmpty()) {
                    add("- category: ${categoryExamples(kind, profile).joinToString("") { "「$it」" }}から1つ選ぶ。")
                } else {
                    // 自分のカテゴリを先に見せ、説明で選ばせる。合うものが無いときだけ例から
                    add("- category: 次から内容に合うものを1つ選ぶ: " + userCategories.joinToString("、") {
                        "「${it.name}」" + if (it.description.isNotEmpty()) "(${it.description})" else ""
                    })
                    add("- どれにも合わないときだけ、${categoryExamples(kind, profile).take(LOCAL_FALLBACK_CATEGORIES).joinToString("") { "「$it」" }}から選ぶ。")
                }
            }
            add("")
            add("例:")
            add("保存内容: 鶏もも肉にしょうゆ・みりん・砂糖のたれを絡めて、皮目から焼く。")
            add("出力: " + output(exampleSummary(style), "\"照り焼き\", \"鶏もも肉\"", "レシピ"))
        }.joinToString("\n")
    }

    /**
     * 出力の形が正しくなかった(JSON でない・項目が無い・要約が空など)ときに、作り直させる指示
     * ([StructuredOutput.generateWithRepair])。端末内 AI では元の指示の後ろに、クラウド API では次の発言として付ける。
     * @param problem 正しくなかった理由([StructuredOutput.problemOf])
     */
    fun repairInstructions(problem: String, scope: AnalysisScope): String = listOf(
        "直前の出力は形式が正しくありませんでした($problem)。",
        "説明やコードブロックを付けず、次の形の JSON だけを出力し直してください。",
        "{" + listOfNotNull(
            """"summary": "要約"""".takeIf { scope != AnalysisScope.TAGS },
            """"tags": ["語", "語"], "category": "分類"""".takeIf { scope != AnalysisScope.SUMMARY }
        ).joinToString(", ") + "}"
    ).joinToString("\n")

    /** 種類ごとの要約の主役([compactAnalyzeInstructions] 用の短い言い方)。 */
    private fun compactGuidance(kind: ContentKind, profile: SummaryProfile): String = compactProfileGuidance(profile) ?: when (kind) {
        ContentKind.WEB_PAGE -> "記事の主張と要点をまとめる"
        ContentKind.VIDEO -> "動画で話している内容の要点をまとめる。あいさつ・宣伝・チャンネル登録のお願いは書かない"
        ContentKind.MEMO -> "書かれている要点をそのまま短くまとめる"
        ContentKind.IMAGE -> "何の画像か(レシート・スクショなど)と、重要な文字や数値(店名・金額・日付)を書く"
        ContentKind.DOCUMENT -> "文書の種類と目的、重要な数値や日付を書く"
        ContentKind.AUDIO -> "話の主題と要点、決まったことを書く"
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
    private fun inputHeadingGuide(kind: ContentKind, profile: SummaryProfile = SummaryProfile.GENERAL): List<String> = buildList {
        when (profile) {
            SummaryProfile.REPOSITORY ->
                add("- 「リポジトリ:」「説明:」「言語:」「トピック:」などは GitHub の情報、「README:」より後はリポジトリの README の本文。")
            SummaryProfile.SOURCE_CODE ->
                add("- 保存内容はコードそのもの。先頭に「リポジトリ:」「ファイル:」があれば、そのコードの置き場所。")
            else -> Unit
        }
        if (kind == ContentKind.VIDEO) {
            add("- 「字幕:」「字幕(自動生成):」の後は動画の字幕。自動生成の字幕は誤認識や句読点の欠落を含む。")
            add("- 「チャプター:」は投稿者が付けた動画の章立て(話の流れの手がかり)、「キーワード:」は投稿者が付けた検索用の語。")
        }
        add("- 「--- 読み取った文字 ---」のような「--- 〜 ---」の行は、ファイルから取り出した部分(画像内の文字・PDF のテキスト・音声の文字起こしなど)の見出し。読み取りの誤りを含むことがある。")
        add("- 「[部分 1/3]」のような見出しは、長い本文を分けて要約したメモの区切り。")
    }

    /** 型ごとの、種類ごとの方針に足す方針(型が [SummaryProfile.GENERAL] なら何も足さない)。 */
    internal fun profileGuidance(profile: SummaryProfile): List<String> = when (profile) {
        SummaryProfile.GENERAL -> emptyList()
        SummaryProfile.PAPER -> listOf(
            "- これは論文。研究の目的、手法、主な結果、結論の順に要点を選び、精度・件数などの数値は残す。",
            "- 著者名・所属・謝辞・参考文献の一覧は要点にしない。"
        )
        SummaryProfile.REFERENCE -> listOf(
            "- これは技術資料・リファレンス。何のための資料か(API・機能・手順)と、使い方の要点を書く。",
            "- 対象のバージョン、非推奨・注意点・制限が書かれていれば残す。目次やリンクの一覧は要点にしない。"
        )
        SummaryProfile.QA -> listOf(
            "- これは質問と回答のページ。何に困っている質問かを先に書き、続けて採用された・評価の高い回答の結論と理由を書く。",
            "- 回答が無い・解決していないときは、その旨を書く。投稿者名・投票数は書かない。"
        )
        SummaryProfile.SOCIAL_POST -> listOf(
            "- これは SNS の投稿。投稿の主張・伝えたいことを書く。短い投稿は言い換えすぎず、そのまま要点にする。",
            "- 投稿者名・ハンドル・ハッシュタグの羅列・いいね/リポストの数・「返信」「フォロー」などの画面の文言は書かない。"
        )
        SummaryProfile.PRODUCT -> listOf(
            "- これは商品ページ。何の商品か(種類・名称)と、主な特徴・仕様・価格を書く。",
            "- 広告のうたい文句・レビューの引用・配送やポイントの案内は要点にしない。価格や在庫は変わるため、ページに書かれたものとして書く。"
        )
        SummaryProfile.RECIPE -> listOf(
            "- これはレシピ。何の料理か、主な材料と、作り方の流れ(調理時間・人数が分かれば)を書く。",
            "- 投稿者の思い出話・コメント欄・広告は要点にしない。"
        )
        SummaryProfile.LIST -> listOf(
            "- これは項目を並べたメモ。何のリスト(ToDo・買い物・読みたい本など)かと、主な項目を書く。",
            "- 全項目を写さず、多いときは件数も添える。完了済み(チェック済み)の項目は、残りと分けて書く。"
        )
        SummaryProfile.REPOSITORY -> listOf(
            "- これは GitHub のリポジトリ。何のためのプロジェクトか(目的)を先に書き、続けて主な機能、使っている言語・技術、使い方(導入や実行の方法)の要点を書く。",
            "- スター数・フォーク数・更新日・バッジ・コントリビューションやライセンスの定型の案内は、要点でなければ書かない。アーカイブ済み(開発が終わっている)ならその旨を書く。",
            "- README が無い・短いときは、説明・言語・トピックから分かる範囲で書く。"
        )
        SummaryProfile.SOURCE_CODE -> listOf(
            "- これはソースコード。何をするコードか(目的・主な機能)を先に書き、続けて言語、主なクラス・関数、使っているライブラリや外部サービスを要点にする。",
            "- コードを1行ずつ説明しない。import・ライセンス表記・自動生成のヘッダー・コメントアウトされたコードは要点にしない。",
            "- コードから分からない動作は推測で補わない。コードの一部しか無いときは、その範囲で書く。"
        )
    }

    /** [compactAnalyzeInstructions] 用の、型の短い言い方。型が [SummaryProfile.GENERAL] なら null(種類の既定を使う)。 */
    private fun compactProfileGuidance(profile: SummaryProfile): String? = when (profile) {
        SummaryProfile.GENERAL -> null
        SummaryProfile.PAPER -> "論文の目的・手法・主な結果・結論をまとめる。数値は残し、著者や参考文献は書かない"
        SummaryProfile.REFERENCE -> "何の資料か(API・機能・手順)と使い方の要点、注意点をまとめる"
        SummaryProfile.QA -> "何に困っている質問かと、回答の結論をまとめる"
        SummaryProfile.SOCIAL_POST -> "投稿の主張をまとめる。投稿者名・ハッシュタグ・いいねの数は書かない"
        SummaryProfile.PRODUCT -> "何の商品かと、主な特徴・仕様・価格をまとめる。広告のうたい文句は書かない"
        SummaryProfile.RECIPE -> "何の料理かと、主な材料・作り方の流れをまとめる"
        SummaryProfile.LIST -> "何のリストかと主な項目をまとめる。多いときは件数も書く"
        SummaryProfile.REPOSITORY -> "何のためのプロジェクトかと、主な機能・使っている技術・使い方をまとめる。スター数やバッジは書かない"
        SummaryProfile.SOURCE_CODE -> "何をするコードかと、言語・主な関数・使っているライブラリをまとめる。1行ずつは説明しない"
    }

    /** 本文が有料・会員限定ページの一部だけのときの方針([LimitedAccess])。 */
    private const val LIMITED_ACCESS_RULE =
        "- 本文の先頭に「${LimitedAccess.MARKER}」の行があるときは、有料・会員限定などで冒頭しか取得できていない。取得できた範囲だけを要約し、続きを推測で補わない。"

    private const val COMPACT_LIMITED_ACCESS_RULE =
        "- 本文に「${LimitedAccess.MARKER}」とあれば冒頭だけの取得。見えている範囲だけを要約し、続きは補わない。"

    /** category の例(型があれば、型に合う候補を先に出す)。 */
    internal fun categoryExamples(kind: ContentKind, profile: SummaryProfile): List<String> {
        val first = when (profile) {
            SummaryProfile.PAPER -> listOf("論文")
            SummaryProfile.REFERENCE -> listOf("技術")
            SummaryProfile.QA -> listOf("技術", "学習")
            SummaryProfile.SOCIAL_POST -> listOf("ニュース", "趣味")
            SummaryProfile.PRODUCT -> listOf("買い物")
            SummaryProfile.RECIPE -> listOf("レシピ")
            SummaryProfile.LIST -> listOf("ToDo", "買い物")
            SummaryProfile.REPOSITORY, SummaryProfile.SOURCE_CODE -> listOf("技術")
            SummaryProfile.GENERAL -> emptyList()
        }
        return (first + categoryExamples(kind)).distinct()
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

    /** summary の項目の説明(出力形式の例の中身)。書き方と言語に合わせる。 */
    internal fun summaryFieldText(style: SummaryStyle, maxChars: Int): String {
        val limit = style.language.limitPhrase(maxChars)
        return when (style.format) {
            SummaryFormat.PARAGRAPH -> "${limit}の要約"
            SummaryFormat.BULLETS -> "${limit}の箇条書きの要約(行頭は「・」、行は \\n で区切る)"
            SummaryFormat.ONE_LINE -> "${limit}の1文の要約"
        }
    }

    /** summary のルール(言語と書き方)。日本語の文章は今までのルール1行だけ。 */
    internal fun summaryRules(style: SummaryStyle): List<String> {
        val language = style.language.promptName
        return buildList {
            add(
                when (style.format) {
                    SummaryFormat.PARAGRAPH -> "- summary は${language}で簡潔に。"
                    SummaryFormat.BULLETS ->
                        "- summary は${language}で、要点を3〜5行の箇条書きにする。各行は短い1文で、行頭を「・」にし、行の間は改行(\\n)で区切る。"
                    SummaryFormat.ONE_LINE -> "- summary は${language}で、1文だけにする。改行しない。"
                }
            )
            if (style.language != SummaryLanguage.JAPANESE) {
                add("- 本文が別の言語でも、summary は必ず${language}で書く。固有名詞・製品名・技術用語は原文のままでよい。")
            }
        }
    }

    /** [compactAnalyzeInstructions] の summary の行に足す、書き方の短い指示。文章なら空。 */
    private fun compactFormatHint(style: SummaryStyle): String = when (style.format) {
        SummaryFormat.PARAGRAPH -> ""
        SummaryFormat.BULLETS -> "3〜5行の箇条書き(行頭は「・」、行は \\n で区切る)。"
        SummaryFormat.ONE_LINE -> "1文だけ。"
    }

    /** [compactAnalyzeInstructions] の出力の例の要約。言語と書き方に合わせる(例が日本語のままだと、他の言語の出力が日本語に寄る)。 */
    private fun exampleSummary(style: SummaryStyle): String {
        val bullets = style.format == SummaryFormat.BULLETS
        return when (style.language) {
            SummaryLanguage.JAPANESE ->
                if (bullets) "・鶏もも肉を皮目から焼く\\n・しょうゆ・みりん・砂糖のたれを絡める" else "鶏もも肉を甘辛いたれで焼く照り焼きの作り方。"
            SummaryLanguage.ENGLISH ->
                if (bullets) "・Pan-fry chicken thighs skin side down\\n・Coat with a soy sauce, mirin and sugar glaze" else "How to make teriyaki chicken thighs in a sweet-savory glaze."
            SummaryLanguage.CHINESE ->
                if (bullets) "・鸡腿肉从带皮的一面开始煎\\n・裹上酱油、味醂和砂糖的酱汁" else "用甜咸酱汁煎鸡腿肉的照烧做法。"
            SummaryLanguage.KOREAN ->
                if (bullets) "・닭다릿살을 껍질 쪽부터 굽는다\\n・간장, 미림, 설탕 양념을 입힌다" else "달콤짭짤한 양념으로 닭다릿살을 굽는 데리야키 만드는 법."
        }
    }

    /** 書き方([style])に合わせた要約の長さの上限。[summaryMaxChars] の上限を、箇条書きは長く・1 行は短くする。 */
    fun summaryMaxChars(kind: ContentKind, isLong: Boolean, style: SummaryStyle): Int =
        style.maxChars(summaryMaxChars(kind, isLong))

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

    /** プロンプトに入れるユーザーのカテゴリの数と説明の長さ(クラウド API は余裕があり、端末内は文脈長が小さいため少なめ) */
    const val API_MAX_CATEGORIES = 30
    const val API_MAX_CATEGORY_DESCRIPTION_CHARS = 80
    const val LOCAL_MAX_CATEGORIES = 8
    const val LOCAL_MAX_CATEGORY_DESCRIPTION_CHARS = 24

    /** 端末内 AI で、自分のカテゴリに合わないときの候補として見せる例の数 */
    private const val LOCAL_FALLBACK_CATEGORIES = 3
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

    /**
     * プロンプトに入れるユーザーのカテゴリ。先頭から [maxCount] 個まで、説明は [maxDescriptionChars] 文字までに切り詰める。
     * 名前が空・長すぎる(AI が返せない長さの)ものは入れない。
     */
    internal fun categoriesForPrompt(
        categories: List<CategoryDefinition>,
        maxCount: Int,
        maxDescriptionChars: Int
    ): List<CategoryDefinition> = categories
        .map { CategoryDefinition(it.name.trim(), it.description.trim().take(maxDescriptionChars)) }
        .filter { it.name.isNotEmpty() && it.name.length <= CategoryNames.MAX_NAME_CHARS }
        .take(maxCount)

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
    fun partInstructions(kind: ContentKind, noteMaxChars: Int, profile: SummaryProfile = SummaryProfile.GENERAL): String = buildList {
        add("あなたは長い保存内容(${kindLabel(kind)})を読むアシスタントです。次の本文は全体の一部です。")
        add("この部分の要点を、後で全体の要約を作るためのメモとして書き出し、JSONのみを出力してください。")
        add("説明文やコードブロックは出力しないでください。")
        add("")
        add("出力形式:")
        add("""{"notes": "この部分の要点(${noteMaxChars}文字以内の日本語)"}""")
        add("")
        add("方針:")
        addAll(kindGuidance(kind))
        addAll(profileGuidance(profile))
        add("- 固有名詞・数値・結論は残す。本文に書かれていないことは書かない。")
        add("- この部分に要点が無ければ notes は空文字にする。")
    }.joinToString("\n")

    /** 要約([text])を [target] の言語に翻訳させる指示(JSON で返させる。#24)。 */
    fun translateInstructions(text: String, target: SummaryLanguage): String = listOf(
        "次の文章を${target.promptName}に翻訳し、JSONのみを出力してください。",
        "説明文やコードブロックは出力しないでください。",
        "",
        "出力形式:",
        """{"translation": "翻訳した文章"}""",
        "",
        "ルール:",
        "- 意味を変えず、足したり省いたりしない。",
        "- 固有名詞・製品名・技術用語は、訳さずに原文のままでもよい。",
        "- 箇条書きの行頭の「・」と、行の区切りの改行(\\n)は保つ。",
        "",
        "翻訳する文章:",
        text
    ).joinToString("\n")

    /** 会話の古い発言を、続きの会話で思い出すためのメモに要約させる指示(端末内 AI 向けの短いもの)。 */
    fun conversationMemoryInstructions(maxChars: Int): String = buildList {
        add("次の会話を、この先の会話で思い出すためのメモにまとめ、JSONのみを出力してください。")
        add("説明文やコードブロックは出力しないでください。")
        add("")
        add("出力形式:")
        add("""{"notes": "会話の要点(${maxChars}文字以内の日本語)"}""")
        add("")
        add("方針:")
        add("- 話題、分かったこと、決まったこと、ユーザーの好みや条件、まだ答えていない質問を残す。")
        add("- これまでの要約があれば、必要なことを残して新しい発言と1つにまとめる。")
        add("- あいさつは書かない。会話に無いことは書かない。")
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
            add("あなたは EchoMark のアシスタントです。保存したブックマークを手がかりに、質問へ日本語の短い文章で答えます。箇条書きは手順や比較だけ。")
            add("- 保存内容を使った文には [1] のように番号を付ける。保存内容に無いことは「一般的には」と分ける。")
            add("- あいさつや雑談には、保存内容に触れずに自然に答える。")
        } else {
            add("あなたは EchoMark のアシスタントです。ユーザーが保存したブックマーク(記事・動画・メモ・ファイル)を手がかりに、質問へ日本語で的確に答えます。")
            add("")
            add("答え方:")
            add("- 最初に答えを1〜2文で書き、続きも段落に分けた自然な文章で書く。前置きや質問の繰り返しは書かない。")
            add("- 箇条書きや短い見出しは、手順や比較のように形にした方が読みやすいときだけ Markdown で使う。")
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
