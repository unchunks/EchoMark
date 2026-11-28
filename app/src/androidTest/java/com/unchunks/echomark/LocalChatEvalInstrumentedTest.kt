package com.unchunks.echomark

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.unchunks.components.llm.ondevice.LiteRtLmTextEngine
import com.unchunks.echomark.data.ai.AiPrompts
import com.unchunks.echomark.data.ai.AnalysisSchema
import com.unchunks.echomark.data.ai.StructuredOutput
import com.unchunks.echomark.data.ai.local.AnalysisParser
import com.unchunks.echomark.data.ai.local.LocalChatPrompt
import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.model.AnalysisScope
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking

/**
 * 実機で、端末内 AI(LiteRT-LM)のチャットの回答を、書式(プロンプトテンプレート)とサンプリングの組み合わせごとに比べる(手動で動かす評価)。
 * モデルは `/data/local/tmp/llm/` に置く(`adb push <model> /data/local/tmp/llm/`)。ファイル名は引数 modelFile で変えられる
 * (.litertlm も旧形式の .task も)。回答は Logcat(タグ ChatEval)に出す。モデルが無ければ skip する。
 */
@RunWith(AndroidJUnit4::class)
class LocalChatEvalInstrumentedTest {

    /**
     * LiteRT-LM で答えさせる(モデルに入っている会話の書式を LiteRT-LM が付ける)。Qwen3 の「考える」は止める。
     * 引数 litertlm に「温度,topK,topP」を「/」区切りで渡すと、その組み合わせごとに比べる。
     */
    @Test
    fun LiteRtLmでチャットの回答を比べる() {
        val args = InstrumentationRegistry.getArguments()
        val file = File(MODEL_DIR, args.getString("modelFile") ?: DEFAULT_MODEL_FILE)
        assumeTrue("モデルが無い: $file", file.isFile)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val samplers = (args.getString("litertlm") ?: "0.8,40,0.95").split("/").map { spec ->
            val (t, k, p) = spec.split(",")
            Triple(t.toDouble(), k.toInt(), p.toDouble())
        }
        val loadStart = SystemClock.elapsedRealtime()
        Engine(
            EngineConfig(
                modelPath = file.absolutePath,
                backend = if (args.getString("backend") == "gpu") Backend.GPU() else Backend.CPU(),
                maxNumTokens = args.getString("maxTokens")?.toInt() ?: MAX_TOKENS,
                cacheDir = context.cacheDir.path
            )
        ).use { engine ->
            engine.initialize()
            Log.i(TAG, "LiteRT-LM の読み込み: ${SystemClock.elapsedRealtime() - loadStart} ms")
            for ((temperature, topK, topP) in samplers) {
                for ((name, prompt) in prompts(args)) {
                    val config = ConversationConfig(
                        samplerConfig = SamplerConfig(topK = topK, topP = topP, temperature = temperature),
                        thinkingConfig = ThinkingConfig(enableThinking = false)
                    )
                    val start = SystemClock.elapsedRealtime()
                    var firstMillis = -1L
                    val output = StringBuilder()
                    engine.createConversation(config).use { conversation ->
                        runBlocking {
                            conversation.sendMessageAsync(prompt)
                                .takeWhile { output.length <= MAX_OUTPUT_CHARS }
                                .collect {
                                    if (firstMillis < 0) firstMillis = SystemClock.elapsedRealtime() - start
                                    output.append(it.toString())
                                }
                        }
                    }
                    val report = "=== litertlm ${args.getString("backend") ?: "cpu"} t=$temperature k=$topK p=$topP / $name " +
                        "(prompt=${prompt.length}ch first=$firstMillis ms total=${SystemClock.elapsedRealtime() - start} ms, " +
                        "${output.length} 文字)\n$output"
                    report.chunked(LOG_CHUNK).forEach { Log.i(TAG, it) }
                }
            }
        }
    }

    /**
     * 生成の途中で cancelProcess を呼んでから、終わりの知らせ(onDone / onError)が来るまでの時間を測る。
     * 引数 cancelAfterMs で、生成を始めてから中断を頼むまでの時間を変える(プロンプトの読み込み中・文字の生成中)。
     */
    @Test
    fun LiteRtLmの中断を測る() {
        val args = InstrumentationRegistry.getArguments()
        val file = File(MODEL_DIR, args.getString("modelFile") ?: DEFAULT_MODEL_FILE)
        assumeTrue("モデルが無い: $file", file.isFile)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cancelAfter = args.getString("cancelAfterMs")?.toLong() ?: 3_000L
        Engine(
            EngineConfig(
                modelPath = file.absolutePath,
                backend = Backend.CPU(),
                maxNumTokens = MAX_TOKENS,
                cacheDir = context.cacheDir.path
            )
        ).use { engine ->
            engine.initialize()
            val conversation = engine.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(topK = 20, topP = 0.8, temperature = 0.5),
                    thinkingConfig = ThinkingConfig(enableThinking = false)
                )
            )
            val done = CountDownLatch(1)
            var how = "?"
            var chars = 0
            val start = SystemClock.elapsedRealtime()
            conversation.sendMessageAsync(prompts(args).first().second, object : com.google.ai.edge.litertlm.MessageCallback {
                override fun onMessage(message: com.google.ai.edge.litertlm.Message) {
                    chars += message.toString().length
                }

                override fun onDone() {
                    how = "onDone"
                    done.countDown()
                }

                override fun onError(throwable: Throwable) {
                    how = "onError(${throwable.javaClass.simpleName}: ${throwable.message?.take(80)})"
                    done.countDown()
                }
            })
            Thread.sleep(cancelAfter)
            val cancelAt = SystemClock.elapsedRealtime()
            conversation.cancelProcess()
            val finished = done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            Log.i(TAG, "=== cancel after=${cancelAfter}ms chars=$chars finished=$finished how=$how " +
                "stop=${SystemClock.elapsedRealtime() - cancelAt} ms total=${SystemClock.elapsedRealtime() - start} ms")
            conversation.close()
        }
    }

    /** 入力と出力の上限(maxTokens)ごとに、読み込みと生成の後のメモリ(PSS)を測る。 */
    @Test
    fun LiteRtLmのメモリを測る() {
        val args = InstrumentationRegistry.getArguments()
        val file = File(MODEL_DIR, args.getString("modelFile") ?: DEFAULT_MODEL_FILE)
        assumeTrue("モデルが無い: $file", file.isFile)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val maxTokens = args.getString("maxTokens")?.toInt() ?: MAX_TOKENS
        val before = android.os.Debug.getPss()
        Engine(
            EngineConfig(
                modelPath = file.absolutePath,
                backend = if (args.getString("backend") == "gpu") Backend.GPU() else Backend.CPU(),
                maxNumTokens = maxTokens,
                cacheDir = context.cacheDir.path
            )
        ).use { engine ->
            engine.initialize()
            val loaded = android.os.Debug.getPss()
            val out = StringBuilder()
            engine.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(topK = 20, topP = 0.8, temperature = 0.5),
                    thinkingConfig = ThinkingConfig(enableThinking = false)
                )
            ).use { conversation ->
                runBlocking { conversation.sendMessageAsync(prompts(args).last().second).collect { out.append(it.toString()) } }
            }
            val generated = android.os.Debug.getPss()
            Log.i(TAG, "=== memory maxTokens=$maxTokens before=${before / 1024}MB loaded=${loaded / 1024}MB generated=${generated / 1024}MB")
        }
    }

    /** 1つの会話に続けて送ったときの、送るごとの最初の文字までの時間(会話を作る費用と分けるため)。 */
    @Test
    fun LiteRtLmで同じ会話に続けて送る時間を測る() {
        val args = InstrumentationRegistry.getArguments()
        val file = File(MODEL_DIR, args.getString("modelFile") ?: DEFAULT_MODEL_FILE)
        assumeTrue("モデルが無い: $file", file.isFile)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Engine(
            EngineConfig(
                modelPath = file.absolutePath,
                backend = if (args.getString("backend") == "gpu") Backend.GPU() else Backend.CPU(),
                maxNumTokens = MAX_TOKENS,
                cacheDir = context.cacheDir.path
            )
        ).use { engine ->
            engine.initialize()
            repeat(2) { round ->
                val createStart = SystemClock.elapsedRealtime()
                engine.createConversation(
                    ConversationConfig(
                        samplerConfig = SamplerConfig(topK = 20, topP = 0.8, temperature = 0.5),
                        thinkingConfig = ThinkingConfig(enableThinking = false)
                    )
                ).use { conversation ->
                    val createMillis = SystemClock.elapsedRealtime() - createStart
                    listOf("こんにちは", "ありがとう", "さようなら").forEachIndexed { i, text ->
                        val start = SystemClock.elapsedRealtime()
                        var first = -1L
                        val out = StringBuilder()
                        runBlocking {
                            conversation.sendMessageAsync(text).collect {
                                if (first < 0) first = SystemClock.elapsedRealtime() - start
                                out.append(it.toString())
                            }
                        }
                        Log.i(TAG, "=== reuse round=$round create=$createMillis ms send=$i first=$first ms " +
                            "total=${SystemClock.elapsedRealtime() - start} ms: $out")
                    }
                }
            }
        }
    }

    /**
     * 要約・タグ・カテゴリの指示の書き方ごとに、LiteRT-LM の解析の出力を比べる(アプリの JSON の生成と同じサンプリング)。
     * 引数 analysisVariants(「full,compact」の「,」区切り)と analysisInputs で絞り込める。結果はアプリのキャッシュの
     * analysis_eval.txt にも書く(`adb exec-out run-as <applicationId> cat cache/analysis_eval.txt`)。
     */
    @Test
    fun LiteRtLmで解析の指示を比べる() {
        val args = InstrumentationRegistry.getArguments()
        val file = File(MODEL_DIR, args.getString("modelFile") ?: DEFAULT_MODEL_FILE)
        assumeTrue("モデルが無い: $file", file.isFile)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val variants = args.getString("analysisVariants")?.split(",") ?: listOf("full", "compact")
        val inputs = ANALYSIS_INPUTS.filter { args.getString("analysisInputs")?.split(",")?.contains(it.name) ?: true }
        Engine(
            EngineConfig(
                modelPath = file.absolutePath,
                backend = if (args.getString("backend") == "gpu") Backend.GPU() else Backend.CPU(),
                maxNumTokens = MAX_TOKENS,
                cacheDir = context.cacheDir.path
            )
        ).use { engine ->
            engine.initialize()
            // 引数 schema: off(縛らない)・on(アプリと同じ JSON スキーマで制約付きの生成)・both(両方を比べる。既定)
            val schemaModes = when (args.getString("schema")) {
                "off" -> listOf(false)
                "on" -> listOf(true)
                else -> listOf(false, true)
            }
            for (input in inputs) {
                for (variant in variants) for (withSchema in schemaModes) {
                    val prompt = analysisPrompt(variant, input)
                    val start = SystemClock.elapsedRealtime()
                    val output = StringBuilder()
                    val responseFormat = if (withSchema) {
                        ResponseFormat.json(
                            AnalysisSchema.analysis(
                                AnalysisScope.ALL,
                                AnalysisSchema.Limits(summaryMaxChars = AiPrompts.summaryMaxChars(input.kind, false) * 3)
                            )
                        )
                    } else {
                        null
                    }
                    val result = runCatching {
                        engine.createConversation(
                            ConversationConfig(
                                samplerConfig = SamplerConfig(topK = 20, topP = 0.8, temperature = 0.2),
                                thinkingConfig = ThinkingConfig(enableThinking = false),
                                enableResponseFormat = withSchema
                            )
                        ).use { conversation ->
                            runBlocking {
                                conversation.sendMessageAsync(prompt, responseFormat = responseFormat)
                                    .takeWhile { output.length <= MAX_OUTPUT_CHARS && !output.contains('}') }
                                    .collect { output.append(it.toString()) }
                            }
                        }
                    }
                    val source = input.title + "\n" + input.body
                    val parsed = AnalysisParser.keepMentionedTags(AnalysisParser.parse(output.toString(), source), source)
                    val report = "=== analysis $variant schema=$withSchema / ${input.name} " +
                        "(prompt=${prompt.length}ch total=${SystemClock.elapsedRealtime() - start} ms" +
                        (result.exceptionOrNull()?.let { ", 失敗: ${it.javaClass.simpleName}: ${it.message?.take(200)}" } ?: "") +
                        ")\n$output\n-> problem=${StructuredOutput.problemOf(output.toString(), AnalysisScope.ALL)} " +
                        "tags=${parsed.tags} category=${parsed.category}\n"
                    Log.i(TAG, report)
                    // Logcat の領域があふれて前の結果が消えることがあるため、アプリのキャッシュにも書く(run-as で読む)
                    File(context.cacheDir, "analysis_eval.txt").appendText(report)
                }
            }
        }
    }

    private class AnalysisSample(val name: String, val kind: ContentKind, val title: String, val body: String)

    /** full はクラウド API 向けの指示、compact は端末内 AI 向けの短い指示(アプリが端末内で使うもの)。 */
    private fun analysisPrompt(variant: String, input: AnalysisSample): String {
        val instructions = when (variant) {
            "full" -> AiPrompts.analyzeInstructions(input.kind, emptyList())
            else -> AiPrompts.compactAnalyzeInstructions(input.kind, emptyList())
        }
        return instructions + "\n\n" + AiPrompts.analyzeInput(input.title, input.body, 3_000)
    }

    private fun prompts(args: android.os.Bundle): List<Pair<String, String>> = listOf(
        "tiny" to "こんにちは",
        "long" to ("次の文章を100文字で要約してください。\n" + "Jetpack Compose は Android の UI を宣言的に書くための仕組みで、状態が変わると画面の該当部分だけを描き直す。".repeat(40)).take(2100),
        "greeting" to LocalChatPrompt.build("こんにちは", emptyList(), emptyList()),
        "rag" to LocalChatPrompt.build(
            "Compose の画面を速くするには？",
            listOf(
                "[1] Jetpack Compose のパフォーマンス改善(3日前に保存): 再コンポーズを減らすために remember と derivedStateOf を使い分け、" +
                    "Lazy リストには key を指定する方法を紹介している。",
                "[2] Kotlin コルーチン入門(10日前に保存): launch と async の違いと、構造化された並行性の考え方を説明している。"
            ),
            emptyList()
        )
    ).filter { args.getString("prompts")?.split(",")?.contains(it.first) ?: true }

    /**
     * 旧形式(.task)のモデルを LiteRT-LM で動かし、会話の書式を「モデルのファイルに入っているもの」と
     * 「Gemma の書式を明示したもの」(アプリが .task の Gemma で使うもの)で比べる。
     * 会話を作れない(書式がファイルに入っていない)ときは、その失敗を出す。
     * 例: `-e modelFile gemma3-1b-it-int4.task`。引数 chatTemplates(「model,gemma」の「,」区切り)で絞り込める。
     */
    @Test
    fun LiteRtLmで旧形式のtaskの書式を比べる() {
        val args = InstrumentationRegistry.getArguments()
        val file = File(MODEL_DIR, args.getString("modelFile") ?: DEFAULT_TASK_MODEL_FILE)
        assumeTrue("モデルが無い: $file", file.isFile)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val templates = listOf("model" to null, "gemma" to LiteRtLmTextEngine.GEMMA_CHAT_TEMPLATE)
            .filter { args.getString("chatTemplates")?.split(",")?.contains(it.first) ?: true }
        val loadStart = SystemClock.elapsedRealtime()
        Engine(
            EngineConfig(
                modelPath = file.absolutePath,
                backend = if (args.getString("backend") == "gpu") Backend.GPU() else Backend.CPU(),
                maxNumTokens = MAX_TOKENS,
                cacheDir = context.cacheDir.path
            )
        ).use { engine ->
            engine.initialize()
            Log.i(TAG, "LiteRT-LM で .task を読み込み: ${SystemClock.elapsedRealtime() - loadStart} ms")
            for ((templateName, template) in templates) {
                for ((name, prompt) in prompts(args)) {
                    val start = SystemClock.elapsedRealtime()
                    val output = StringBuilder()
                    val result = runCatching {
                        engine.createConversation(
                            ConversationConfig(
                                samplerConfig = SamplerConfig(topK = 20, topP = 0.8, temperature = 0.5),
                                thinkingConfig = ThinkingConfig(enableThinking = false),
                                chatTemplate = template
                            )
                        ).use { conversation ->
                            runBlocking {
                                conversation.sendMessageAsync(prompt)
                                    .takeWhile { output.length <= MAX_OUTPUT_CHARS }
                                    .collect { output.append(it.toString()) }
                            }
                        }
                    }
                    val report = "=== task template=$templateName / $name " +
                        "(total=${SystemClock.elapsedRealtime() - start} ms, ${output.length} 文字" +
                        (result.exceptionOrNull()?.let { ", 失敗: ${it.javaClass.simpleName}: ${it.message?.take(200)}" } ?: "") +
                        ")\n$output"
                    report.chunked(LOG_CHUNK).forEach { Log.i(TAG, it) }
                }
            }
        }
    }

    private companion object {
        const val TAG = "ChatEval"
        const val MODEL_DIR = "/data/local/tmp/llm"
        const val DEFAULT_MODEL_FILE = "model.litertlm"
        const val DEFAULT_TASK_MODEL_FILE = "model.task"
        const val MAX_TOKENS = 4096
        const val MAX_OUTPUT_CHARS = 800
        const val TIMEOUT_SECONDS = 180L
        const val LOG_CHUNK = 3_000

        /** 解析の比較に使う保存内容(実機で保存した各種類のブックマークの本文に近いもの) */
        val ANALYSIS_INPUTS = listOf(
            AnalysisSample(
                "recipe", ContentKind.MEMO, "echomark_recipe",
                "肉じゃがの作り方(4人分)\n\n材料: じゃがいも4個、玉ねぎ1個、にんじん1本、牛こま切れ肉200g、しらたき1袋、だし400ml、" +
                    "しょうゆ大さじ3、みりん大さじ3、砂糖大さじ2。\n\n1. じゃがいもは皮をむいて一口大に切り、水にさらす。\n" +
                    "2. 鍋に油を熱して牛肉を炒め、野菜としらたきを加えて炒める。\n3. だしを入れて煮立て、砂糖・みりん・しょうゆを加える。\n" +
                    "4. 落としぶたをして中火で15分ほど煮る。\n\nポイント: 火を止めて10分置くと、味がよくしみる。"
            ),
            AnalysisSample(
                "receipt", ContentKind.IMAGE, "echomark_ocr_test",
                "--- 画像の内容 ---\n画像内の文字:\n領収書 EchoMark テスト\n合計1,980円 2026年10月19日\nCompose performance tips"
            ),
            AnalysisSample(
                "kotlin", ContentKind.WEB_PAGE, "Kotlin - Wikipedia",
                "Kotlin はジェットブレインズが開発した静的型付けのオブジェクト指向プログラミング言語。2011年に発表され、" +
                    "2016年に Kotlin 1.0 が公開された。Java 仮想マシン上で動き、Java と相互に呼び出せる。Null 安全、拡張関数、" +
                    "コルーチンなどの機能を持つ。2019年に Google は Kotlin を Android アプリ開発の推奨言語にした。" +
                    "2024年の Kotlin 2.0 では K2 コンパイラが安定版になった。名前はサンクトペテルブルク近くのコトリン島に由来する。"
            ),
            AnalysisSample(
                "minutes", ContentKind.DOCUMENT, "echomark_minutes_scan",
                "--- 読み取った文字 ---\n会議議事録\n日時: 2026年10月20日 14:00〜15:00\n出席者: 佐藤、鈴木、田中\n" +
                    "1. 新製品の発売日を12月1日に決定した。\n2. 広告予算は300万円とする。\n3. 次回の会議は10月27日に行う。\n" +
                    "担当: 鈴木がプレスリリースを作成する。"
            ),
            AnalysisSample(
                "voice", ContentKind.AUDIO, "echomark_voice_memo",
                "--- 音声の文字起こし ---\n来週の旅行の準備についてのメモです。金曜日の朝8時に東京駅から新幹線で京都へ向かいます。" +
                    "持ち物はカメラ、充電器、雨具です。1日目は清水寺と祇園を観光し、2日目は嵐山で竹林を歩く予定です。" +
                    "ホテルの予約は今日中に済ませます。"
            )
        )
    }
}
