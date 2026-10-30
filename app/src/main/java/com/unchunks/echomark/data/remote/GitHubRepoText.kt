package com.unchunks.echomark.data.remote

import org.json.JSONObject
import java.util.Locale

/**
 * GitHub のリポジトリの情報(API の応答)と README を、AI に渡す本文にする(#14)。純粋関数(JVM 単体テスト可能)。
 * GitHub のリポジトリのページは、HTML のままだとファイル一覧・ナビゲーションなどが本文に混ざり、README が切り詰められやすい。
 * API の説明・言語・トピックと README の本文だけを、見出しつきでまとめる。
 */
object GitHubRepoText {
    /** リポジトリの本文の上限(README が主役のため、通常のページ([HtmlContentExtractor.MAX_TEXT_CHARS])より長く取る) */
    const val MAX_TEXT_CHARS = 8_000

    /** [fullName] は `owner/repo` */
    data class Info(
        val fullName: String,
        val description: String?,
        val language: String?,
        val topics: List<String>,
        val license: String?,
        val stars: Int,
        val forks: Int,
        /** 最後にプッシュした日(`2026-10-01`) */
        val pushedOn: String?,
        val archived: Boolean,
        val homepage: String?
    )

    /** `GET /repos/{owner}/{repo}` の応答から取り出す。JSON でなければ null。 */
    fun parseInfo(json: String): Info? = try {
        val obj = JSONObject(json)
        val fullName = obj.optString("full_name").takeIf { it.isNotBlank() } ?: return null
        Info(
            fullName = fullName,
            description = text(obj, "description"),
            language = text(obj, "language"),
            topics = obj.optJSONArray("topics")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf { topic -> topic.isNotEmpty() } }
            }.orEmpty(),
            // SPDX の識別子(「MIT」など)。「NOASSERTION」(判定できなかった)は載せない
            license = obj.optJSONObject("license")?.let { text(it, "spdx_id") }?.takeIf { it != "NOASSERTION" },
            stars = obj.optInt("stargazers_count"),
            forks = obj.optInt("forks_count"),
            pushedOn = text(obj, "pushed_at")?.take(DATE_CHARS),
            archived = obj.optBoolean("archived"),
            homepage = text(obj, "homepage")
        )
    } catch (_: Exception) {
        null
    }

    /** ブックマークのタイトル(`owner/repo: 説明`。説明が無ければ `owner/repo`) */
    fun title(info: Info): String {
        val description = info.description?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return info.fullName
        val title = "${info.fullName}: $description"
        return if (title.length <= MAX_TITLE_CHARS) title else title.take(MAX_TITLE_CHARS - 1) + "…"
    }

    /** AI に渡す本文。README が無ければ情報だけ。[maxChars] を超える分は README を切る。 */
    fun build(info: Info, readme: String?, maxChars: Int = MAX_TEXT_CHARS): String {
        val header = listOfNotNull(
            "リポジトリ: ${info.fullName}",
            info.description?.let { "説明: ${it.replace(WHITESPACE, " ").trim()}" },
            info.language?.let { "言語: $it" },
            info.topics.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "トピック: "),
            info.license?.let { "ライセンス: $it" },
            "スター: ${"%,d".format(Locale.US, info.stars)} ・ フォーク: ${"%,d".format(Locale.US, info.forks)}",
            info.pushedOn?.let { "最終更新: $it" },
            "状態: アーカイブ済み(開発は終わっています)".takeIf { info.archived },
            info.homepage?.let { "公式サイト: $it" }
        ).joinToString("\n")
        val body = readme?.let { cleanReadme(it) }?.takeIf { it.isNotBlank() }
            ?: return header.take(maxChars)
        return (header + "\n\nREADME:\n" + body).take(maxChars)
    }

    /**
     * README(Markdown)から、読む助けにならない部分を除く。バッジ・画像・HTML のタグ・コメント・リンクの定義を除き、
     * `[文字](URL)` は文字だけにする。コードブロック(導入のコマンドなど)と見出し・箇条書きは残す。
     */
    fun cleanReadme(markdown: String): String {
        var inFence = false
        val lines = mutableListOf<String>()
        for (raw in markdown.replace(HTML_COMMENT, "").lineSequence()) {
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) {
                inFence = !inFence
                lines += line
                continue
            }
            if (inFence) {
                lines += line
                continue
            }
            val cleaned = line
                .replace(IMAGE, "")
                .replace(LINK) { match -> match.groupValues[1].ifEmpty { match.groupValues[2] } }
                .replace(HTML_TAG, "")
                .trim()
            // 画像やバッジだけの行・リンクの定義の行は、空になるか定義の形なので除く
            if (LINK_DEFINITION.matches(cleaned)) continue
            lines += if (cleaned.isEmpty() && line.isNotBlank()) "" else cleaned
        }
        // 続く空行は1つにまとめる
        return lines.joinToString("\n").replace(BLANK_LINES, "\n\n").trim()
    }

    private fun text(obj: JSONObject, key: String): String? =
        if (obj.isNull(key)) null else obj.optString(key).trim().takeIf { it.isNotEmpty() }

    private const val DATE_CHARS = 10
    private const val MAX_TITLE_CHARS = 80

    private val WHITESPACE = Regex("""\s+""")
    private val HTML_COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val HTML_TAG = Regex("""</?[A-Za-z][^>]*>""")

    /** `![alt](url)`(バッジ・スクリーンショット)。リンクで包んだ `[![alt](img)](url)` は、中の画像を除いた後に LINK が残りを処理する */
    private val IMAGE = Regex("""!\[[^\]]*]\([^)]*\)""")

    /** `[文字](URL)` と、`[文字][参照]` */
    private val LINK = Regex("""\[([^\]]*)]\([^)]*\)|\[([^\]]*)]\[[^\]]*]""")
    private val LINK_DEFINITION = Regex("""\[[^\]]+]:\s*\S+.*""")
    private val BLANK_LINES = Regex("""\n{3,}""")
}
