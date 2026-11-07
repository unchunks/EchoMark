package com.unchunks.echomark.domain.bookmark.model

import java.net.URI

/**
 * 要約の指示を、[ContentKind](Web ページ・動画・メモなど)よりもう一段細かく変えるための「要約の型」(#17)。
 * 同じ Web ページでも、論文・技術資料・Q&A・SNS の投稿・商品ページ・レシピでは、要約で拾うべきものが違う。
 * 型は URL のホスト・パスと本文から決める([summaryProfileOf])。決められなければ [GENERAL](種類ごとの既定の指示)。
 *
 * 型を足すときは、ここに値を足し、[summaryProfileOf] の判定と AiPrompts の指示(型ごとの方針)を足す。
 * GitHub のリポジトリ([REPOSITORY]、#14)とソースコード([SOURCE_CODE]、#15)もこの型。
 *
 * @property kinds この型を使える中身の種類(これ以外の種類には当てはめない)
 */
enum class SummaryProfile(val kinds: Set<ContentKind>) {
    /** 種類ごとの既定(型なし) */
    GENERAL(ContentKind.entries.toSet()),

    /** 論文(arXiv・学術誌のページ、論文の PDF) */
    PAPER(setOf(ContentKind.WEB_PAGE, ContentKind.DOCUMENT)),

    /** 技術資料・リファレンス・公式ドキュメント */
    REFERENCE(setOf(ContentKind.WEB_PAGE)),

    /** 質問と回答のページ(Stack Overflow・知恵袋など) */
    QA(setOf(ContentKind.WEB_PAGE)),

    /** SNS の投稿 */
    SOCIAL_POST(setOf(ContentKind.WEB_PAGE)),

    /** 商品ページ */
    PRODUCT(setOf(ContentKind.WEB_PAGE)),

    /** レシピ */
    RECIPE(setOf(ContentKind.WEB_PAGE)),

    /** GitHub のリポジトリのページ(README と、説明・言語・トピックなどのメタデータで要約する) */
    REPOSITORY(setOf(ContentKind.WEB_PAGE)),

    /** ソースコード(コードのファイル・GitHub のファイル・Gist・メモに貼ったコード) */
    SOURCE_CODE(setOf(ContentKind.WEB_PAGE, ContentKind.MEMO)),

    /** 項目を並べたメモ(ToDo・買い物リストなど) */
    LIST(setOf(ContentKind.MEMO))
}

/** このブックマークの要約の型。 */
fun Bookmark.summaryProfile(): SummaryProfile = summaryProfileOf(
    kind = contentKind(),
    url = contentUri.takeIf { type == BookmarkType.URL },
    title = title,
    text = content.orEmpty(),
    fileName = fileName,
    mimeType = mimeType
)

/**
 * 要約の型を決める(純粋関数)。
 * @param kind 中身の種類
 * @param url リンク先の URL(URL のブックマークだけ。無ければ null)
 * @param title ブックマークのタイトル
 * @param text 取り出した本文(型を決める手がかりにする。長ければ先頭だけ見る)
 * @param fileName 保存したファイルの名前(共有したコードのファイルなど。無ければ null)
 * @param mimeType 保存したファイルの MIME タイプ(無ければ null)
 */
fun summaryProfileOf(
    kind: ContentKind,
    url: String?,
    title: String,
    text: String,
    fileName: String? = null,
    mimeType: String? = null
): SummaryProfile {
    val profile = when (kind) {
        ContentKind.WEB_PAGE, ContentKind.DOCUMENT -> profileOfUrl(url) ?: profileOfText(kind, title, text)
        ContentKind.MEMO -> when {
            isSourceCodeFile(fileName ?: title, mimeType) -> SummaryProfile.SOURCE_CODE
            looksLikeSourceCode(text) -> SummaryProfile.SOURCE_CODE
            looksLikeList(text) -> SummaryProfile.LIST
            else -> null
        }
        ContentKind.VIDEO, ContentKind.IMAGE, ContentKind.AUDIO -> null
    }
    return profile?.takeIf { kind in it.kinds } ?: SummaryProfile.GENERAL
}

private fun profileOfUrl(url: String?): SummaryProfile? {
    // GitHub のリポジトリのページ・コードのファイル・Gist(ホストの判定より先に見る)
    if (GitHubUrls.repoOf(url) != null) return SummaryProfile.REPOSITORY
    if (GitHubUrls.codeFileOf(url) != null || isSourceCodeUrl(url)) return SummaryProfile.SOURCE_CODE
    val uri = try {
        URI(url?.trim().orEmpty())
    } catch (_: Exception) {
        return null
    }
    val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
    val path = uri.path.orEmpty().lowercase()
    fun hostIs(vararg names: String) = names.any { host == it || host.endsWith(".$it") }
    if (host == "gist.github.com") return SummaryProfile.SOURCE_CODE
    return when {
        hostIs(
            "arxiv.org", "openreview.net", "aclanthology.org", "pubmed.ncbi.nlm.nih.gov", "doi.org", "semanticscholar.org",
            "jstage.jst.go.jp", "ieeexplore.ieee.org", "dl.acm.org", "sciencedirect.com", "researchgate.net", "biorxiv.org"
        ) -> SummaryProfile.PAPER
        hostIs("nature.com") && path.startsWith("/articles") -> SummaryProfile.PAPER
        host == "link.springer.com" && path.startsWith("/article") -> SummaryProfile.PAPER
        hostIs("stackoverflow.com", "stackexchange.com", "teratail.com", "quora.com", "chiebukuro.yahoo.co.jp", "okwave.jp", "askubuntu.com") ->
            SummaryProfile.QA
        hostIs("x.com", "twitter.com", "bsky.app", "threads.net", "threads.com", "facebook.com", "instagram.com", "reddit.com", "mastodon.social") ->
            SummaryProfile.SOCIAL_POST
        hostIs("amazon.co.jp", "amazon.com") && (path.contains("/dp/") || path.contains("/gp/product/")) -> SummaryProfile.PRODUCT
        hostIs("kakaku.com", "yodobashi.com", "biccamera.com") || host == "item.rakuten.co.jp" -> SummaryProfile.PRODUCT
        hostIs("cookpad.com", "kurashiru.com", "delishkitchen.tv", "recipe.rakuten.co.jp", "macaro-ni.jp", "oceans-nadia.com", "orangepage.net") ->
            SummaryProfile.RECIPE
        hostIs(
            "developer.android.com", "developer.mozilla.org", "learn.microsoft.com", "docs.oracle.com",
            "readthedocs.io", "docs.python.org", "developer.apple.com", "docs.aws.amazon.com"
        ) || host == "kotlinlang.org" && path.startsWith("/docs") ||
            host.startsWith("docs.") || host.startsWith("developer.") || host.startsWith("developers.") ||
            path.startsWith("/docs/") || path.startsWith("/reference/") || path.startsWith("/api/") -> SummaryProfile.REFERENCE
        else -> null
    }
}

/** URL で決められなかったとき、本文の特徴で決める(論文の PDF・レシピなど)。 */
private fun profileOfText(kind: ContentKind, title: String, text: String): SummaryProfile? {
    val head = text.take(TEXT_PROBE_CHARS)
    return when {
        // 論文: 「Abstract」と「参考文献」(References)の両方がある。PDF の論文は URL の手がかりが無いことが多い
        kind == ContentKind.DOCUMENT && PAPER_ABSTRACT.containsMatchIn(text.take(PAPER_PROBE_CHARS)) &&
            PAPER_REFERENCES.containsMatchIn(text) -> SummaryProfile.PAPER
        // レシピ: 材料と作り方(または手順)がそろっている
        kind == ContentKind.WEB_PAGE && head.contains("材料") && (head.contains("作り方") || head.contains("手順")) -> SummaryProfile.RECIPE
        else -> null
    }
}

/** 本文の行の大半が箇条書き・チェックボックス・番号つきなら、項目を並べたメモ。 */
private fun looksLikeList(text: String): Boolean {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.size < MIN_LIST_LINES) return false
    return lines.count { LIST_LINE.containsMatchIn(it) } * 100 >= lines.size * LIST_LINE_PERCENT
}

private const val TEXT_PROBE_CHARS = 3_000
private const val PAPER_PROBE_CHARS = 6_000
private const val MIN_LIST_LINES = 3
private const val LIST_LINE_PERCENT = 60

private val PAPER_ABSTRACT = Regex("""(?i)\babstract\b|^\s*要旨|概要\s*[:：]""", RegexOption.MULTILINE)
private val PAPER_REFERENCES = Regex("""(?i)\breferences\b|参考文献""")
private val LIST_LINE = Regex("""^(?:[-*・•●○□☐☑✓]|\[[ xX]?]|\d{1,2}[.)．、])\s*\S""")
