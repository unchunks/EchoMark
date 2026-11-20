package com.unchunks.echomark.crash

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 記録の種類。ファイル名にも入れる。 */
enum class CrashLogKind(val fileTag: String, val label: String) {
    /** 捕まえられずにアプリが落ちた例外 */
    CRASH("crash", "クラッシュ"),

    /** ワーカー(バックグラウンド処理)が例外で失敗した */
    WORKER("worker", "ワーカーの失敗"),

    /** アプリが握りつぶさず Timber.e で記録したエラー */
    ERROR("error", "エラー")
}

/** 端末とアプリの情報。ログの先頭に書く。 */
data class CrashEnvironment(
    val appVersion: String,
    val device: String,
    val osVersion: String
)

/** 保存済みの1件。ファイルの中身が [text]。 */
data class CrashLogEntry(
    val name: String,
    val timestampMillis: Long,
    val kind: CrashLogKind?,
    val text: String
)

/**
 * クラッシュなどの記録を、アプリ専用領域の [dir] にテキストファイルとして保存する。通信はせず、外には出さない。
 * 1件1ファイル(`<時刻ms>-<種類>.txt`)で、[maxFiles] 件を超えたら古いものから削除し、
 * 1件が [maxFileChars] 文字を超えたら後ろを切り捨てる(総量は maxFiles × maxFileChars 文字まで)。
 * 書き込みで例外を投げない(記録の失敗でアプリの動作や、クラッシュ処理そのものを妨げないため)。
 */
class CrashLogStore(
    private val dir: File,
    private val environment: () -> CrashEnvironment,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
    private val maxFileChars: Int = DEFAULT_MAX_FILE_CHARS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()

    /** 1件を保存する。保存できたファイル名、失敗したら null。 */
    fun record(kind: CrashLogKind, throwable: Throwable, threadName: String, note: String? = null): String? =
        try {
            synchronized(lock) {
                dir.mkdirs()
                val now = clock()
                val name = uniqueName(now, kind)
                File(dir, name).writeText(format(now, kind, throwable, threadName, note), Charsets.UTF_8)
                trim()
                name
            }
        } catch (e: Throwable) {
            null
        }

    /** 新しい順の一覧。 */
    fun entries(): List<CrashLogEntry> = synchronized(lock) {
        files().mapNotNull { file ->
            try {
                CrashLogEntry(file.name, timestampOf(file.name), kindOf(file.name), file.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.timestampMillis }
    }

    fun count(): Int = synchronized(lock) { files().size }

    /** すべての記録を、新しい順に1つの文章にまとめる(共有・書き出し用)。なければ null。 */
    fun combinedText(): String? {
        val all = entries()
        if (all.isEmpty()) return null
        return all.joinToString("\n\n$SEPARATOR\n\n") { it.text.trimEnd() } + "\n"
    }

    /** すべて削除する。 */
    fun clear() {
        synchronized(lock) { files().forEach { it.delete() } }
    }

    private fun files(): List<File> =
        dir.listFiles { f -> f.isFile && FILE_NAME.matches(f.name) }?.toList().orEmpty()

    private fun uniqueName(now: Long, kind: CrashLogKind): String {
        var time = now
        // 同じミリ秒に2件書かれても上書きしない
        while (File(dir, "$time-${kind.fileTag}.txt").exists()) time++
        return "$time-${kind.fileTag}.txt"
    }

    /** 古いものから削除して [maxFiles] 件に収める。 */
    private fun trim() {
        val sorted = files().sortedBy { timestampOf(it.name) }
        sorted.take((sorted.size - maxFiles).coerceAtLeast(0)).forEach { it.delete() }
    }

    private fun format(now: Long, kind: CrashLogKind, throwable: Throwable, threadName: String, note: String?): String {
        val env = environment()
        val header = buildString {
            appendLine("EchoMark ${kind.label}")
            appendLine("日時: ${TIME_FORMAT.format(Instant.ofEpochMilli(now))}")
            appendLine("バージョン: ${env.appVersion}")
            appendLine("端末: ${env.device}")
            appendLine("OS: ${env.osVersion}")
            appendLine("スレッド: $threadName")
            if (note != null) appendLine("補足: $note")
            appendLine("----")
        }
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val text = header + trace
        return if (text.length <= maxFileChars) text else text.take(maxFileChars) + "\n…(長いため以降を省略)\n"
    }

    companion object {
        const val DEFAULT_MAX_FILES = 20
        const val DEFAULT_MAX_FILE_CHARS = 60_000
        private const val SEPARATOR = "========================================"
        private val FILE_NAME = Regex("""\d+-(crash|worker|error)\.txt""")
        private val TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())

        private fun timestampOf(name: String): Long = name.substringBefore('-').toLongOrNull() ?: 0L

        private fun kindOf(name: String): CrashLogKind? =
            CrashLogKind.entries.firstOrNull { name.endsWith("-${it.fileTag}.txt") }
    }
}
