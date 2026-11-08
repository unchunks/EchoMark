package com.unchunks.echomark.ui.chat

import com.unchunks.echomark.domain.chat.RagSupport
import com.unchunks.echomark.domain.model.ChatTemplate

/** チャットのテンプレート。最初から入っているものと、ユーザーが保存したものの扱い。Android 依存なしの純粋関数。 */
object ChatTemplates {
    /** 保存しておけるテンプレートの最大数 */
    const val MAX_USER_TEMPLATES = 30

    /** 一覧に出す名前の最大文字数 */
    private const val TITLE_MAX_CHARS = 24

    val builtIn: List<ChatTemplate> = listOf(
        ChatTemplate("builtin-compare", "比べて整理する", "次のテーマについて保存した内容を比べて、共通点と違いを整理して: "),
        ChatTemplate("builtin-explain", "やさしく説明する", "次のテーマを、初めての人にも分かるように説明して: "),
        ChatTemplate("builtin-study", "学ぶ順番を考える", "次のテーマについて、保存した内容から学ぶ順番を提案して: "),
        ChatTemplate("builtin-todo", "次にやることを出す", "保存した内容をもとに、次にやることを洗い出して: "),
        ChatTemplate("builtin-review", "今週の振り返り", "今週保存したものを振り返って、学んだことをまとめて"),
        ChatTemplate("builtin-gaps", "足りない知識を探す", "次のテーマについて、保存した内容にまだ足りない観点を教えて: ")
    )

    /**
     * [text] を [templates] の先頭に足す。空・同じ文が既にある・上限に達しているときは [templates] のまま返す。
     * @param id 新しいテンプレートの id
     */
    fun add(templates: List<ChatTemplate>, text: String, id: String): List<ChatTemplate> {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || templates.size >= MAX_USER_TEMPLATES || templates.any { it.text == trimmed }) return templates
        return listOf(ChatTemplate(id, RagSupport.titleFrom(trimmed, TITLE_MAX_CHARS), trimmed)) + templates
    }
}
