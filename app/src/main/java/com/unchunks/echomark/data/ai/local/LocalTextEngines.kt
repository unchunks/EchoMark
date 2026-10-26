@file:Suppress("DEPRECATION")

package com.unchunks.echomark.data.ai.local

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import com.google.mediapipe.tasks.genai.llminference.PromptTemplates
import com.unchunks.components.llm.ondevice.EngineBackend
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** 生成の性質。端末内 AI のサンプリングの設定を変える(LiteRT-LM のとき)。 */
internal enum class GenerationKind {
    /** チャットの回答。自然な文章にする */
    CHAT,

    /** 要約・タグ・資料の選択などの JSON。ぶれを小さくする */
    JSON
}

/**
 * 1回の生成。増分と終わりは [LocalTextEngine.start] に渡した関数で受け取る。
 * 終わりの知らせを受け取った後に、呼び出し側のスレッドで [close] を呼ぶ(知らせを送るエンジンのスレッドの中で閉じると、
 * エンジンがその知らせの処理の終わりを待つため止まる)。
 */
internal interface LocalGeneration {
    /** 生成を止めるよう頼む。止まると(止まる前に終わっていればそのとき)終わりの関数が呼ばれる。 */
    fun cancel()

    /** セッション・会話を閉じる。終わりの知らせを受け取ってから呼ぶ。 */
    fun close()
}

/**
 * 端末内 AI の推論エンジン。モデルの形式で、MediaPipe の LLM Inference([MediaPipeTextEngine])か
 * LiteRT-LM([LiteRtLmTextEngine])を使う。同時に動かせる生成は1つ(呼び出し側で直列化する)。
 */
internal interface LocalTextEngine : AutoCloseable {
    /**
     * [prompt] への回答の生成を始める(待たずに戻る)。生成された文字列の増分を [onPartial] に、
     * 終わり(成功なら null、失敗・中断なら例外)を [onDone] に渡す。どちらもエンジンのスレッドから呼ばれる。
     */
    fun start(
        prompt: String,
        kind: GenerationKind,
        onPartial: (String) -> Unit,
        onDone: (Throwable?) -> Unit
    ): LocalGeneration
}

/**
 * MediaPipe の LLM Inference(.task 形式のモデル)。1回の生成ごとにセッションを作る
 * (会話の履歴はプロンプトに含めるため、セッションに状態を持たせない)。
 * @param templates 会話の書式(ユーザーとモデルの区切り)。null ならモデルのファイルに入っているものを使う
 */
internal class MediaPipeTextEngine(
    private val engine: LlmInference,
    private val templates: PromptTemplates?
) : LocalTextEngine {

    override fun start(
        prompt: String,
        kind: GenerationKind,
        onPartial: (String) -> Unit,
        onDone: (Throwable?) -> Unit
    ): LocalGeneration {
        val options = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .apply { templates?.let { setPromptTemplates(it) } }
            .build()
        val session = LlmInferenceSession.createFromOptions(engine, options)
        val future = try {
            session.addQueryChunk(prompt)
            session.generateResponseAsync(ProgressListener<String> { partial, _ -> if (partial.isNotEmpty()) onPartial(partial) })
        } catch (e: Throwable) {
            runCatching { session.close() }
            throw e
        }
        future.addListener({
            val error = try {
                future.get()
                null
            } catch (e: ExecutionException) {
                e.cause ?: e
            } catch (e: Throwable) {
                e
            }
            onDone(error)
        }, Runnable::run)
        return object : LocalGeneration {
            override fun cancel() {
                if (!future.isDone) runCatching { session.cancelGenerateResponseAsync() }
            }

            // 生成が終わってから閉じる(生成中に閉じると、解放したネイティブの資源を使ってしまう)
            override fun close() {
                runCatching { session.close() }
            }
        }
    }

    override fun close() = engine.close()
}

/**
 * LiteRT-LM(.litertlm 形式のモデル)。Qwen など SentencePiece 以外のトークナイザーのモデルは、MediaPipe の LLM Inference では
 * 読み込めない(「SentencePiece tokenizer is not found in the model」)ため、こちらで動かす。
 * 会話の書式はモデルのファイルに入っているもの(Jinja のテンプレート)を LiteRT-LM が付ける。
 * Qwen3 などの「考える」(回答の前に <think> で考えを書く)は止める(時間がかかり、回答に考えの文が混ざるため)。
 * 1回の生成ごとに会話を作り、終わったら呼び出し側で閉じる(会話の履歴はプロンプトに含める)。
 */
internal class LiteRtLmTextEngine(private val engine: Engine) : LocalTextEngine {

    override fun start(
        prompt: String,
        kind: GenerationKind,
        onPartial: (String) -> Unit,
        onDone: (Throwable?) -> Unit
    ): LocalGeneration {
        val conversation = engine.createConversation(
            ConversationConfig(
                samplerConfig = samplerOf(kind),
                thinkingConfig = ThinkingConfig(enableThinking = false)
            )
        )
        val finished = AtomicBoolean(false)
        val filter = ThinkTagFilter()
        fun finish(error: Throwable?) {
            if (finished.compareAndSet(false, true)) onDone(error)
        }
        try {
            conversation.sendMessageAsync(prompt, object : MessageCallback {
                override fun onMessage(message: Message) {
                    filter.accept(message.toString()).takeIf { it.isNotEmpty() }?.let(onPartial)
                }

                override fun onDone() {
                    filter.flush().takeIf { it.isNotEmpty() }?.let(onPartial)
                    finish(null)
                }

                override fun onError(throwable: Throwable) = finish(throwable)
            })
        } catch (e: Throwable) {
            finish(e)
        }
        return object : LocalGeneration {
            override fun cancel() {
                if (!finished.get()) runCatching { conversation.cancelProcess() }
            }

            override fun close() {
                runCatching { conversation.close() }
            }
        }
    }

    override fun close() = engine.close()

    companion object {
        /**
         * [modelPath] のモデルを読み込む(数秒〜十数秒かかる。呼び出し側のスレッドで行う)。
         * @param maxNumTokens 入力と出力の合計の上限(KV キャッシュの大きさ)
         * @param cacheDir 読み込みの準備の結果を置く場所(2回目以降の読み込みが速くなる)
         */
        fun create(modelPath: String, backend: EngineBackend, maxNumTokens: Int, cacheDir: String): LiteRtLmTextEngine {
            val engine = Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = when (backend) {
                        EngineBackend.GPU -> Backend.GPU()
                        EngineBackend.CPU -> Backend.CPU()
                    },
                    maxNumTokens = maxNumTokens,
                    cacheDir = cacheDir
                )
            )
            try {
                engine.initialize()
            } catch (e: Throwable) {
                runCatching { engine.close() }
                throw e
            }
            return LiteRtLmTextEngine(engine)
        }

        /**
         * サンプリングの設定。Qwen3 の考えない使い方の推奨(temperature 0.7・topP 0.8・topK 20)を元に、
         * 小さいモデルで話がそれないよう温度を下げる。JSON はさらに下げて、形式が崩れにくくする。
         */
        private fun samplerOf(kind: GenerationKind): SamplerConfig = when (kind) {
            GenerationKind.CHAT -> SamplerConfig(topK = 20, topP = 0.8, temperature = 0.5)
            GenerationKind.JSON -> SamplerConfig(topK = 20, topP = 0.8, temperature = 0.2)
        }
    }
}

/**
 * 出力の「<think>…</think>」(推論モデルが回答の前に書く考え)を取り除く。増分ごとに受け取り、流してよい部分を返す。
 * 「考える」を止めていても、モデルによっては空の <think></think> を書くため、念のため除く。
 */
internal class ThinkTagFilter {
    private val pending = StringBuilder()
    private var inThink = false

    fun accept(chunk: String): String {
        pending.append(chunk)
        val out = StringBuilder()
        while (pending.isNotEmpty()) {
            if (inThink) {
                val end = pending.indexOf(CLOSE)
                if (end < 0) {
                    // 閉じタグが分かれて届くことがあるため、その長さの分だけ残して捨てる
                    keepTail(CLOSE.length - 1)
                    break
                }
                pending.delete(0, end + CLOSE.length)
                inThink = false
                // 考えの後の改行は回答の頭に付けない
                while (pending.isNotEmpty() && pending[0].isWhitespace()) pending.deleteCharAt(0)
            } else {
                val start = pending.indexOf(OPEN)
                if (start >= 0) {
                    out.append(pending, 0, start)
                    pending.delete(0, start + OPEN.length)
                    inThink = true
                    continue
                }
                // 開きタグの途中で分かれているかもしれない末尾は、次の増分まで待つ
                val keep = partialTagSuffix(pending.toString(), OPEN)
                out.append(pending, 0, pending.length - keep)
                pending.delete(0, pending.length - keep)
                break
            }
        }
        return out.toString()
    }

    /** 生成の終わりに、タグの途中かもしれないと待っていた末尾を返す(考えの途中で終わったときは捨てる)。 */
    fun flush(): String {
        val rest = if (inThink) "" else pending.toString()
        pending.setLength(0)
        return rest
    }

    private fun keepTail(length: Int) {
        if (pending.length > length) pending.delete(0, pending.length - length)
    }

    /** [text] の末尾が [tag] の先頭の一部と一致する長さ(タグが2つの増分に分かれて届いたとき)。 */
    private fun partialTagSuffix(text: String, tag: String): Int {
        for (length in minOf(tag.length - 1, text.length) downTo 1) {
            if (text.endsWith(tag.substring(0, length))) return length
        }
        return 0
    }

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}
