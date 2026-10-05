package com.unchunks.echomark.data.ai

/**
 * ローカル LLM とクラウド API で共通のプロンプト文言。
 * 注意: 本文を埋め込むため trimIndent は使わず、行の連結で組み立てる。
 */
object AiPrompts {

    /**
     * 要約・タグ・カテゴリの指示(JSON のみを出力させる)。
     * [existingTags](優先する順)を渡すと、似たタグを増やさないよう、合うものはそのまま使わせる。
     * 渡すのは先頭から [maxExistingTags] 個・合計 [maxExistingTagChars] 文字まで(長すぎるタグ名は飛ばす)。
     */
    fun analyzeInstructions(
        existingTags: List<String>,
        maxExistingTags: Int = API_MAX_EXISTING_TAGS,
        maxExistingTagChars: Int = API_MAX_EXISTING_TAG_CHARS
    ): String {
        val tags = existingTagsForPrompt(existingTags, maxExistingTags, maxExistingTagChars)
        return buildList {
            add("あなたはブックマーク整理アシスタントです。次の保存内容を分析し、JSONのみを出力してください。")
            add("説明文やコードブロックは出力しないでください。")
            add("")
            add("出力形式:")
            add("""{"summary": "100文字以内の日本語の要約", "tags": ["タグ1", "タグ2"], "category": "カテゴリ名1つ"}""")
            add("")
            add("ルール:")
            add("- summary は日本語で簡潔に。")
            add("- tags は内容を表す短い単語を1〜${MAX_TAGS}個。")
            if (tags.isNotEmpty()) {
                add("- tags は、下の「既存のタグ」に内容に合うものがあれば、表記を変えずにそのまま使ってください。")
                add("- 既存のタグに合うものが無いときだけ、新しいタグを作ってください(同じ意味の言い換えや表記ゆれは作らない)。")
            }
            add("- category は「技術」「ニュース」「レシピ」「学習」「仕事」「趣味」「その他」のように短い1語。")
            if (tags.isNotEmpty()) {
                add("")
                add("既存のタグ:")
                add(tags.joinToString(prefix = "[", postfix = "]", separator = ", ") { quote(it) })
            }
        }.joinToString("\n")
    }

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

    /** 分析対象の本文部分。 */
    fun analyzeInput(text: String, maxChars: Int): String =
        listOf("保存内容:", text.take(maxChars)).joinToString("\n")

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
