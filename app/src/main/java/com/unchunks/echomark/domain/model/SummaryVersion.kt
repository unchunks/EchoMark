package com.unchunks.echomark.domain.model

import com.unchunks.echomark.domain.repository.AiTaskSetting
import com.unchunks.echomark.domain.repository.LlmBackend

/** 要約の1版を作った元(#21)。 */
enum class SummarySource(val label: String) {
    /** AI が作った */
    AI("AI"),

    /** ユーザーが手で直した */
    MANUAL("手で編集")
}

/**
 * 要約の履歴の1版(#21)。現在の要約([com.unchunks.echomark.domain.bookmark.model.Bookmark.summary])も、
 * 作った時点で履歴に入っている。
 * @property generator 作った AI の呼び名(手で直した版・分からない版は null)
 * @property style 作ったときの言語と書き方(「日本語・文章」など。分からない版は null)
 */
data class SummaryVersion(
    val id: Long,
    val bookmarkId: Long,
    val summary: String,
    val source: SummarySource,
    val generator: String? = null,
    val style: String? = null,
    val createdAt: Long
)

/** 要約を作った AI と、そのときの言語・書き方(履歴に残す)。 */
data class SummaryOrigin(val generator: String, val style: String)

/** [SummaryStyle] の呼び名(「日本語・文章」)。履歴に残し、版を見比べるときに出す。 */
val SummaryStyle.label: String get() = "${language.label}・${format.label}"

/**
 * 要約に使う AI の設定の呼び名(履歴で、どの AI が作った版かを見分けるため)。
 * 端末内なら選んだモデルの id(既定のモデルなら「端末内 AI」)、クラウド API なら提供元とモデル。
 */
fun AiTaskSetting.generatorLabel(): String = when (backend) {
    LlmBackend.LOCAL -> localModelId?.let { "端末内 AI($it)" } ?: "端末内 AI"
    LlmBackend.API -> "${apiProvider.displayName} $apiModel"
}
