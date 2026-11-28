package com.unchunks.echomark.domain.model

/** AI の分類に渡すカテゴリ1つ(名前と、どんなブックマークを入れるかの説明)。 */
data class CategoryDefinition(val name: String, val description: String = "")

/** カテゴリの出どころ。 */
enum class CategoryKind {
    /** ユーザーが作った(名前の変更・説明の編集・削除ができる) */
    USER,

    /** 標準のカテゴリ(AI の分類の候補として、アプリが持っている) */
    BUILT_IN,

    /** どちらでもない、AI が自由に付けた分類 */
    AI_OTHER
}

/**
 * カテゴリ一覧の1行。[description] は何を入れるカテゴリかの説明(AI が自由に付けた分類には、そう分かる定型の説明)。
 * [id] はユーザーが作ったカテゴリだけが持つ。
 */
data class CategoryEntry(
    val name: String,
    val description: String,
    val kind: CategoryKind,
    val bookmarkCount: Int,
    val id: Long? = null
)
