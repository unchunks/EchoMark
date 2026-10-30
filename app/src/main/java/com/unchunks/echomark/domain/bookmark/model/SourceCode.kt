package com.unchunks.echomark.domain.bookmark.model

import java.net.URI

/**
 * ソースコードと GitHub の URL の見分け方(#14・#15)。純粋関数(JVM 単体テスト可能)。
 */

/** GitHub のリポジトリ(`owner/repo`)。 */
data class GitHubRepoRef(val owner: String, val repo: String) {
    val fullName: String get() = "$owner/$repo"
}

/** GitHub のリポジトリの中のファイル([ref] はブランチ・タグ・コミット)。 */
data class GitHubFileRef(val repo: GitHubRepoRef, val ref: String, val path: String) {
    /** ファイルの中身をそのまま返す URL */
    val rawUrl: String get() = "https://raw.githubusercontent.com/${repo.fullName}/$ref/$path"

    val fileName: String get() = path.substringAfterLast('/')
}

/** GitHub のリポジトリの中のディレクトリ([ref] はブランチ・タグ・コミット)。 */
data class GitHubFolderRef(val repo: GitHubRepoRef, val ref: String, val path: String)

object GitHubUrls {

    /** リポジトリのページ(`github.com/owner/repo` と、その中のディレクトリ `/tree/...`)なら、そのリポジトリ。 */
    fun repoOf(url: String?): GitHubRepoRef? {
        val segments = segmentsOf(url) ?: return null
        if (segments.size < 2 || segments[0] in RESERVED_OWNERS) return null
        if (segments.size > 2 && segments[2] != "tree") return null
        val repo = segments[1].removeSuffix(".git")
        if (segments[0].isEmpty() || repo.isEmpty()) return null
        return GitHubRepoRef(segments[0], repo)
    }

    /**
     * リポジトリの中のディレクトリのページ(`/tree/ブランチ/パス`)なら返す。ブランチの直下(`/tree/ブランチ`)は null。
     * ブランチ名に `/` があると区切りを間違えるが、その場合は README が見つからず、通常のページとして扱われる。
     */
    fun folderOf(url: String?): GitHubFolderRef? {
        val repo = repoOf(url) ?: return null
        val segments = segmentsOf(url) ?: return null
        if (segments.size < FOLDER_MIN_SEGMENTS || ".." in segments) return null
        return GitHubFolderRef(repo, ref = segments[3], path = segments.drop(4).joinToString("/"))
    }

    /** ソースコードのファイルのページ(`github.com/owner/repo/blob/<ref>/<path>`)なら、そのファイル。 */
    fun codeFileOf(url: String?): GitHubFileRef? {
        val segments = segmentsOf(url) ?: return null
        if (segments.size < 5 || segments[0] in RESERVED_OWNERS || segments[2] != "blob") return null
        val path = segments.drop(4).joinToString("/")
        if (!isSourceCodeFile(path, null)) return null
        return GitHubFileRef(GitHubRepoRef(segments[0], segments[1].removeSuffix(".git")), ref = segments[3], path = path)
    }

    /** `github.com` のページの、パスの区切りごとの部分。GitHub のページでなければ null。 */
    private fun segmentsOf(url: String?): List<String>? {
        val uri = try {
            URI(url?.trim().orEmpty())
        } catch (_: Exception) {
            return null
        }
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        if (host != "github.com") return null
        return uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
    }

    /** リポジトリではない、GitHub 自身のページの先頭の部分 */
    /** `owner/repo/tree/ブランチ/パス` */
    private const val FOLDER_MIN_SEGMENTS = 5

    private val RESERVED_OWNERS = setOf(
        "settings", "orgs", "topics", "marketplace", "features", "pricing", "about", "login", "join", "sponsors", "explore",
        "collections", "notifications", "pulls", "issues", "search", "trending", "apps", "enterprise", "security", "customer-stories",
        "readme", "new", "organizations", "users", "login", "logout", "site", "premium-support", "resources", "solutions", "events"
    )
}

/**
 * ソースコードのファイルか。ファイル名(またはパス)の拡張子・名前が、プログラミング言語・シェル・SQL のものか、
 * MIME タイプが `text/x-…`(言語ごとのテキスト)・`application/x-sh` などか。
 */
fun isSourceCodeFile(fileName: String?, mimeType: String?): Boolean {
    val name = fileName?.trim()?.substringAfterLast('/')?.lowercase().orEmpty()
    if (name in CODE_FILE_NAMES) return true
    val extension = name.substringAfterLast('.', "")
    if (extension.isNotEmpty() && extension in CODE_EXTENSIONS) return true
    val mime = normalizeMimeType(mimeType) ?: return false
    return mime.startsWith("text/x-") && !mime.startsWith("text/x-markdown") || mime in CODE_MIME_TYPES
}

/** URL の最後の部分がソースコードのファイルか(クエリ・フラグメントは無視する)。 */
fun isSourceCodeUrl(url: String?): Boolean {
    val uri = try {
        URI(url?.trim().orEmpty())
    } catch (_: Exception) {
        return false
    }
    val host = uri.host?.lowercase().orEmpty()
    if (host == "raw.githubusercontent.com" || host == "gist.githubusercontent.com") return true
    // .php・.pl などは HTML のページのことが多いため、URL の拡張子だけでは決めない
    val extension = uri.path.orEmpty().substringAfterLast('/').substringAfterLast('.', "").lowercase()
    return extension !in AMBIGUOUS_URL_EXTENSIONS && isSourceCodeFile(uri.path.orEmpty(), null)
}

/**
 * 本文がソースコードに見えるか(メモに貼ったコード・拡張子の無いコード)。空行を除いた行の過半数が、コードによくある形
 * (文の終わりの `;`・`{`・`}`、`import`・`def`・`fun` などの書き出し、`=>`・`->`、インデントされた `)`・`]`)なら true。
 * 文章を貼ったメモを誤ってコードにしないよう、行数が少ないとき(5 行未満)は false。
 */
fun looksLikeSourceCode(text: String): Boolean {
    val lines = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.take(CODE_PROBE_LINES)
    if (lines.size < MIN_CODE_LINES) return false
    return lines.count { CODE_LINE.containsMatchIn(it.trim()) } * 100 >= lines.size * CODE_LINE_PERCENT
}

/** URL の拡張子では、コードのファイルか Web ページか決められない拡張子 */
private val AMBIGUOUS_URL_EXTENSIONS = setOf("php", "pl", "r", "m", "mm", "v", "h", "c", "cc", "ex", "exs", "cs")

private const val CODE_PROBE_LINES = 60
private const val MIN_CODE_LINES = 5
private const val CODE_LINE_PERCENT = 55

private val CODE_LINE = Regex(
    """[;{}]\s*$|^\s*(?:import|package|from|using|include|#include|#!|def|fun|func|function|class|interface|struct|enum|impl|fn|let|const|val|var|public|private|protected|static|return|if|else|elif|for|while|switch|case|try|catch|@)\b|^[})\]]+[;,)]*$|=>|->|\)\s*\{|^\s*//|^\s*#\s|::|\w\(.*\)\s*$"""
)

private val CODE_EXTENSIONS = setOf(
    "kt", "kts", "java", "py", "pyi", "js", "jsx", "mjs", "cjs", "ts", "tsx", "go", "rs", "c", "h", "cpp", "cc", "cxx", "hpp", "cs",
    "swift", "m", "mm", "rb", "php", "sh", "bash", "zsh", "fish", "ps1", "bat", "sql", "lua", "dart", "scala", "r", "pl", "hs", "ex", "exs",
    "erl", "clj", "groovy", "gradle", "vue", "svelte", "zig", "nim", "jl", "v", "sol", "proto", "tf", "cmake"
)

/** 拡張子の無いコードのファイル名 */
private val CODE_FILE_NAMES = setOf("dockerfile", "makefile", "gemfile", "rakefile", "jenkinsfile", "vagrantfile", "cmakelists.txt")

private val CODE_MIME_TYPES = setOf(
    "application/x-sh", "application/x-shellscript", "application/javascript", "application/x-javascript", "application/typescript",
    "application/x-python", "application/x-python-code", "application/x-httpd-php", "application/sql", "application/x-sql"
)
