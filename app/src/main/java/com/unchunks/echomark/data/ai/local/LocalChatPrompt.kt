package com.unchunks.echomark.data.ai.local

import com.unchunks.components.llm.ChatRole
import com.unchunks.components.llm.ondevice.LocalChatInput
import com.unchunks.components.llm.ondevice.LocalChatTurn
import com.unchunks.echomark.data.ai.AiPrompts
import com.unchunks.echomark.domain.model.ChatMessage

/**
 * ローカル LLM のチャット用プロンプトを組み立てる。Android に依存しない純粋な処理で、単体テストできる。
 *
 * ローカル LLM は入力と出力の合計トークン数に上限(LocalLlmProvider の MAX_TOKENS)があるため、
 * 文字数で予算を割り振って超えないようにする(日本語は概ね 1 文字 ≒ 1 トークン以下として見積もる)。
 * 優先順位は 質問(上限 [MAX_USER_MESSAGE_CHARS]) > 会話の記憶(古い発言の要約。上限 [MAX_MEMORY_CHARS]) >
 * 文脈(回答の根拠) > 会話履歴(新しいものから)。
 * 指示・文脈・記憶はシステムの指示に、履歴は発言ごとに分けて渡す([input])。役割はモデルの書式で付く
 * (Gemma にはシステムロールが無いが、書式が最初のユーザーのターンにまとめる)。
 * 書式が受け付けないモデルには、全部を1つのユーザーターンにまとめたもの([build])を渡す。
 */
internal object LocalChatPrompt {
    /**
     * プロンプト全体の文字数の上限。残りを回答の生成に使う。
     * 遅い端末(Snapdragon 665 で Qwen3 0.6B)では、プロンプトの読み込みに 100 文字あたり約 4 秒かかるため、
     * 回答が始まるまでの時間を抑えるよう少なめにする
     */
    const val MAX_PROMPT_CHARS = 1_800

    /** 質問の文字数の上限(長文を貼り付けられた場合は切り詰める) */
    const val MAX_USER_MESSAGE_CHARS = 800

    /** 会話の記憶の文字数の上限 */
    const val MAX_MEMORY_CHARS = 300

    private const val MAX_CONTEXT_ITEMS = 5
    private const val MAX_HISTORY_ITEMS = 6
    private const val MAX_HISTORY_CHARS = 200

    /** 文脈1件を途中で切ってでも入れる最小の文字数(これより短くなるなら入れない) */
    private const val MIN_CONTEXT_CHARS = 100

    /** 会話の記憶に使う文字数は、使える文字数のこの分の1まで(入力を減らしてやり直すとき、文脈の分を残す) */
    private const val MEMORY_BUDGET_DIVISOR = 3

    private const val HISTORY_HEADER = "これまでの会話:"
    private const val MEMORY_HEADER = "さらに前の会話の要約:"
    private const val QUESTION_PREFIX = "質問: "
    private const val ELLIPSIS = "…"

    /** 全部を1つのユーザーターンにまとめたプロンプト。引数は [input] と同じ。 */
    fun build(
        userMessage: String,
        context: List<String>,
        history: List<ChatMessage>,
        maxChars: Int = MAX_PROMPT_CHARS,
        memory: String? = null
    ): String = input(userMessage, context, history, maxChars, memory).plainPrompt

    /**
     * @param context 呼び出し側で "[n] タイトル: 要約" に整形済みの文脈(番号は呼び出し側の付番をそのまま使う)
     * @param history 直近の会話履歴(古い順)
     * @param memory 会話の記憶([history] より前の発言の要約)。無ければ null
     */
    fun input(
        userMessage: String,
        context: List<String>,
        history: List<ChatMessage>,
        maxChars: Int = MAX_PROMPT_CHARS,
        memory: String? = null
    ): LocalChatInput {
        val message = truncate(userMessage.trim(), MAX_USER_MESSAGE_CHARS)
        val question = QUESTION_PREFIX + message
        // 区切りの空行(改行2つ)を除いた、指示・文脈・履歴に使える文字数
        val budget = maxChars - question.length - 2

        // 記憶は文脈に押し出されないよう先に取る(入力を減らしてやり直すときは、一緒に短くする)
        val memorySection = memory?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "$MEMORY_HEADER\n${truncate(it, minOf(MAX_MEMORY_CHARS, budget / MEMORY_BUDGET_DIVISOR))}" }
        val restBudget = budget - (memorySection?.let { it.length + 2 } ?: 0)

        val instructions = buildInstructions(context.take(MAX_CONTEXT_ITEMS), restBudget)
        val systemInstruction = listOfNotNull(instructions, memorySection).joinToString("\n\n")

        // 履歴は新しいものから、入る分だけ残す
        var historyBudget = restBudget - instructions.length - (2 + HISTORY_HEADER.length)
        val kept = ArrayDeque<ChatMessage>()
        val historyLines = ArrayDeque<String>()
        for (entry in history.takeLast(MAX_HISTORY_ITEMS).asReversed()) {
            val speaker = if (entry.role == ChatRole.USER) "ユーザー" else "アシスタント"
            val line = "$speaker: ${truncate(entry.content, MAX_HISTORY_CHARS)}"
            if (line.length + 1 > historyBudget) break
            kept.addFirst(entry)
            historyLines.addFirst(line)
            historyBudget -= line.length + 1
        }

        val plainPrompt = buildList {
            add(systemInstruction)
            if (historyLines.isNotEmpty()) {
                add("")
                add(HISTORY_HEADER)
                addAll(historyLines)
            }
            add("")
            add(question)
        }.joinToString("\n")
        return LocalChatInput(systemInstruction, alternatingTurns(kept), message, plainPrompt)
    }

    /**
     * 発言を、ユーザーから始まって交互に並び、回答で終わる形にする(モデルの書式が交互を求めるため)。
     * 回答に失敗した質問が残っていて同じ側が続くときは新しい方を残し、回答の無い最後の質問は外す。
     */
    private fun alternatingTurns(messages: List<ChatMessage>): List<LocalChatTurn> {
        val turns = ArrayDeque<LocalChatTurn>()
        for (message in messages) {
            val fromUser = message.role == ChatRole.USER
            if (turns.isEmpty() && !fromUser) continue
            if (turns.lastOrNull()?.fromUser == fromUser) turns.removeLast()
            turns.addLast(LocalChatTurn(fromUser, truncate(message.content, MAX_HISTORY_CHARS)))
        }
        if (turns.lastOrNull()?.fromUser == true) turns.removeLast()
        return turns
    }

    /** 文脈を先頭から [budget] に収まるだけ入れた指示文。入りきらない1件は、ある程度入るなら途中で切って入れる。 */
    private fun buildInstructions(context: List<String>, budget: Int): String {
        val selected = mutableListOf<String>()
        for (item in context) {
            if (AiPrompts.chatInstructions(selected + item, compact = true).length <= budget) {
                selected += item
                continue
            }
            val overflow = AiPrompts.chatInstructions(selected + item, compact = true).length - budget
            val keep = item.length - overflow - ELLIPSIS.length
            if (keep >= MIN_CONTEXT_CHARS) selected += item.take(keep) + ELLIPSIS
            break
        }
        return AiPrompts.chatInstructions(selected, compact = true)
    }

    private fun truncate(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else text.take(maxChars - ELLIPSIS.length) + ELLIPSIS
}
