package com.unchunks.echomark.data.ai

/**
 * ローカル LLM とクラウド API で共通のプロンプト文言。
 * 注意: 本文を埋め込むため trimIndent は使わず、行の連結で組み立てる。
 */
object AiPrompts {

    /** 要約・タグ・カテゴリの指示(JSON のみを出力させる)。 */
    val ANALYZE_INSTRUCTIONS: String = listOf(
        "あなたはブックマーク整理アシスタントです。次の保存内容を分析し、JSONのみを出力してください。",
        "説明文やコードブロックは出力しないでください。",
        "",
        "出力形式:",
        """{"summary": "100文字以内の日本語の要約", "tags": ["タグ1", "タグ2", "タグ3"], "category": "カテゴリ名1つ"}""",
        "",
        "ルール:",
        "- summary は日本語で簡潔に。",
        "- tags は内容を表す短い単語を最大5個。",
        "- category は「技術」「ニュース」「レシピ」「学習」「仕事」「趣味」「その他」のように短い1語。"
    ).joinToString("\n")

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
