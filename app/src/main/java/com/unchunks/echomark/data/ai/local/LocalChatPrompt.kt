package com.unchunks.echomark.data.ai.local

import com.unchunks.echomark.data.ai.AiPrompts
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.ChatRole

/**
 * ローカル LLM のチャット用プロンプトを組み立てる。Android に依存しない純粋な処理で、単体テストできる。
 *
 * ローカル LLM は入力と出力の合計トークン数に上限(LocalLlmProvider の MAX_TOKENS)があるため、
 * 文字数で予算を割り振って超えないようにする(日本語は概ね 1 文字 ≒ 1 トークン以下として見積もる)。
 * 優先順位は 質問(上限 [MAX_USER_MESSAGE_CHARS]) > 文脈(回答の根拠) > 会話履歴(新しいものから)。
 * Gemma にはシステムロールが無いため、指示・文脈・履歴を1つのユーザーターンにまとめる。
 */
internal object LocalChatPrompt {
    /** プロンプト全体の文字数の上限。残りを回答の生成に使う */
    const val MAX_PROMPT_CHARS = 2_400

    /** 質問の文字数の上限(長文を貼り付けられた場合は切り詰める) */
    const val MAX_USER_MESSAGE_CHARS = 800

    private const val MAX_CONTEXT_ITEMS = 5
    private const val MAX_HISTORY_ITEMS = 6
    private const val MAX_HISTORY_CHARS = 200

    /** 文脈1件を途中で切ってでも入れる最小の文字数(これより短くなるなら入れない) */
    private const val MIN_CONTEXT_CHARS = 100

    private const val HISTORY_HEADER = "これまでの会話:"
    private const val QUESTION_PREFIX = "質問: "
    private const val ELLIPSIS = "…"

    /**
     * @param context 呼び出し側で "[n] タイトル: 要約" に整形済みの文脈(番号は呼び出し側の付番をそのまま使う)
     * @param history 直近の会話履歴(古い順)
     */
    fun build(
        userMessage: String,
        context: List<String>,
        history: List<ChatMessage>,
        maxChars: Int = MAX_PROMPT_CHARS
    ): String {
        val question = QUESTION_PREFIX + truncate(userMessage.trim(), MAX_USER_MESSAGE_CHARS)
        // 区切りの空行(改行2つ)を除いた、指示・文脈・履歴に使える文字数
        val budget = maxChars - question.length - 2

        val instructions = buildInstructions(context.take(MAX_CONTEXT_ITEMS), budget)

        // 履歴は新しいものから、入る分だけ残す
        var historyBudget = budget - instructions.length - (2 + HISTORY_HEADER.length)
        val historyLines = ArrayDeque<String>()
        for (message in history.takeLast(MAX_HISTORY_ITEMS).asReversed()) {
            val speaker = if (message.role == ChatRole.USER) "ユーザー" else "アシスタント"
            val line = "$speaker: ${truncate(message.content, MAX_HISTORY_CHARS)}"
            if (line.length + 1 > historyBudget) break
            historyLines.addFirst(line)
            historyBudget -= line.length + 1
        }

        return buildList {
            add(instructions)
            if (historyLines.isNotEmpty()) {
                add("")
                add(HISTORY_HEADER)
                addAll(historyLines)
            }
            add("")
            add(question)
        }.joinToString("\n")
    }

    /** 文脈を先頭から [budget] に収まるだけ入れた指示文。入りきらない1件は、ある程度入るなら途中で切って入れる。 */
    private fun buildInstructions(context: List<String>, budget: Int): String {
        val selected = mutableListOf<String>()
        for (item in context) {
            if (AiPrompts.chatInstructions(selected + item).length <= budget) {
                selected += item
                continue
            }
            val overflow = AiPrompts.chatInstructions(selected + item).length - budget
            val keep = item.length - overflow - ELLIPSIS.length
            if (keep >= MIN_CONTEXT_CHARS) selected += item.take(keep) + ELLIPSIS
            break
        }
        return AiPrompts.chatInstructions(selected)
    }

    private fun truncate(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else text.take(maxChars - ELLIPSIS.length) + ELLIPSIS
}
