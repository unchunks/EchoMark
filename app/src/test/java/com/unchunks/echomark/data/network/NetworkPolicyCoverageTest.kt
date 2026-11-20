package com.unchunks.echomark.data.network

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 通信の経路が、オフラインモード([NetworkGate])を抜けていないかをソースで確かめる。
 *
 * 通信できる仕組み(OkHttp・WebView・DownloadManager・地図・クラウド AI の SDK など)を使うファイルは、
 * 下の [rules] で「使ってよいファイル」と「そのファイルがオフラインモードを確かめている印」を決めておく。
 * 新しいファイルで使い始めたら、このテストが失敗する。オフラインモードで止まるようにしてから(共通の OkHttpClient から
 * `newBuilder()` で作る・呼ぶ前に [ensureOnline] で確かめるなど)、ここに理由とともに足すこと。
 * 一覧は `docs/offline-mode.md` にもある。
 */
class NetworkPolicyCoverageTest {

    /**
     * 通信できる仕組みの使い方([pattern])と、使ってよいファイル(main のソースからの相対パス → そのファイルに必要な印)。
     * 印は、オフラインモードを確かめている(または確かめた client を使っている)ことが分かる文字列。
     */
    private class Rule(val what: String, val pattern: Regex, val allowed: Map<String, String>)

    private val rules = listOf(
        Rule(
            "OkHttpClient を新しく作る(共通の client を通らず、オフラインモードのインターセプターが付かない)",
            Regex("""OkHttpClient\.Builder\(\)|OkHttpClient\(\)"""),
            mapOf("com/unchunks/echomark/di/NetworkModule.kt" to "OfflineModeInterceptor(gate)")
        ),
        Rule(
            "WebView(OkHttp を通らない)",
            Regex("""import android\.webkit\.WebView$""", RegexOption.MULTILINE),
            mapOf(
                // ページを読み込んでコピーを作る。読み込む前とページの中の読み込みのたびに確かめる
                "com/unchunks/echomark/ui/snapshot/PageSnapshotter.kt" to "if (isOffline()) offlineBlocked()",
                "com/unchunks/echomark/ui/snapshot/PageSnapshotDialog.kt" to "currentIsOffline()",
                // 保存したファイルだけを表示し、ほかの読み込みはすべて断る(通信しない)
                "com/unchunks/echomark/ui/snapshot/SnapshotViewer.kt" to "isFileRequestAllowed(request.url.toString(), fileUrl)"
            )
        ),
        Rule(
            "DownloadManager(端末の側で通信する)",
            Regex("""import android\.app\.DownloadManager"""),
            mapOf("com/unchunks/echomark/data/ai/model/ModelDownloadClient.kt" to "gate.ensureOnline(")
        ),
        Rule(
            "地図(MapLibre)の通信の client の差し替え",
            Regex("""HttpRequestUtil"""),
            mapOf("com/unchunks/echomark/ui/places/PlaceTileMap.kt" to "MapNetworkEntryPoint::class.java).okHttpClient()")
        ),
        Rule(
            "地図(MapLibre)の画面",
            Regex("""import org\.maplibre\.android\.maps\.MapView"""),
            mapOf("com/unchunks/echomark/ui/places/PlaceTileMap.kt" to "MapNetworkEntryPoint::class.java).okHttpClient()")
        ),
        Rule(
            "クラウド AI の API クライアント(Claude の SDK は OkHttp の共通の client を通らない)",
            Regex("""import com\.unchunks\.components\.llm\.cloud\.(ClaudeApiClient|GeminiApiClient|OpenAiApiClient)|import com\.anthropic\."""),
            mapOf("com/unchunks/echomark/data/ai/api/ApiLlmProvider.kt" to "OfflineGuardedApiLlmClient(it, gate)")
        ),
        Rule(
            "クラウドの文字起こし",
            Regex("""import com\.unchunks\.components\.speech\.(OpenAiTranscriptionClient|GeminiTranscriptionClient)"""),
            mapOf("com/unchunks/echomark/data/extract/transcribe/TranscriptionProviderSelector.kt" to "if (gate.isOffline()) return null")
        ),
        Rule(
            "OkHttp 以外の HTTP・ソケット(オフラインモードを通らない)",
            Regex(
                """import java\.net\.(HttpURLConnection|URL|URLConnection|Socket|DatagramSocket)$|""" +
                    """import javax\.net\.ssl\.HttpsURLConnection|\.openConnection\(|\.openStream\(|""" +
                    """import android\.net\.http\.|import org\.chromium\.net\.|import io\.ktor\.client|""" +
                    """import androidx\.media3\.datasource\.(okhttp|cronet|DefaultHttpDataSource)""",
                RegexOption.MULTILINE
            ),
            emptyMap()
        )
    )

    private val sourceRoot = File("src/main/java")

    private fun sources(): Map<String, String> {
        assertTrue("main のソースが見つからない: ${sourceRoot.absolutePath}", sourceRoot.isDirectory)
        return sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            // 改行が CRLF の作業ツリー(Windows)でも、行末の印($)で比べられるようにそろえる
            .associate { it.relativeTo(sourceRoot).invariantSeparatorsPath to it.readText().replace("\r\n", "\n") }
    }

    @Test
    fun 通信できる仕組みは決めたファイルでだけ使い_そこではオフラインモードを確かめている() {
        val files = sources()
        val problems = mutableListOf<String>()
        for (rule in rules) {
            for ((path, text) in files) {
                if (!rule.pattern.containsMatchIn(text)) continue
                val mark = rule.allowed[path]
                when {
                    mark == null -> problems += "$path: ${rule.what} を使っている。オフラインモードで止まるようにして、" +
                        "NetworkPolicyCoverageTest に足してください"
                    !text.contains(mark) -> problems += "$path: ${rule.what} を使っているが、オフラインモードを確かめる印「$mark」が無い"
                }
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun ML_Kitの利用状況の送信先の登録を外している() {
        // Google Data Transport は HttpURLConnection でアプリの中から送り、オフラインモードで止められない
        val manifest = File("src/main/AndroidManifest.xml").readText().replace("\r\n", "\n")
        assertTrue(
            Regex(
                """android:name="com\.google\.android\.datatransport\.runtime\.backends\.TransportBackendDiscovery"\s+tools:node="remove""""
            ).containsMatchIn(manifest)
        )
    }

    @Test
    fun 使ってよいファイルの一覧が古くなっていない() {
        // ファイルを消した・名前を変えたのに一覧に残っていると、守りの確認が働かない
        val files = sources()
        val stale = rules.flatMap { rule -> rule.allowed.keys.filter { it !in files }.map { "${rule.what}: $it" } }
        assertTrue("見つからないファイル:\n${stale.joinToString("\n")}", stale.isEmpty())
    }
}
