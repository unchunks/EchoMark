package com.unchunks.echomark.data.ai.api

import com.unchunks.echomark.data.ai.LongTextConfig
import com.unchunks.echomark.domain.provider.ApiProvider

/**
 * クラウド API に1回で渡せる本文の長さ(文字数)と、それを超える本文の分割要約の設定。
 *
 * 現行の Claude・Gemini(文脈長 100 万トークン)や OpenAI の文脈長なら、記事や字幕の本文はたいてい
 * 1回の呼び出しで要約できる。分割要約(部分 N 回 + まとめ 1 回)は往復が増えて遅くなるため、上限を超えるときだけ使う。
 * 日本語は 1 文字が 1 トークン前後になりうるため、文脈長に余裕を持たせた文字数にしている。
 * モデル ID は自由入力できるため、文脈長を判断できないモデルは [FALLBACK_CHARS](従来の値)にする。
 */
internal object ApiInputBudget {

    /** 文脈長が分からないモデルの上限(従来の値) */
    const val FALLBACK_CHARS = 20_000

    /** 文脈長 100 万トークン級のモデル(Claude 5 系・Gemini 2 以降) */
    private const val LARGE_CHARS = 150_000

    /** OpenAI の GPT-4.1 以降(文脈長 100 万〜40 万トークン。ChatGPT 用の 12.8 万トークンでも収まる量) */
    private const val OPENAI_CHARS = 100_000

    /** 文脈長 20 万トークンのモデル(Claude 4 系) */
    private const val MEDIUM_CHARS = 60_000

    /** 分割要約で同時に進める部分の数 */
    const val PARALLELISM = 4

    private val CLAUDE_MODEL = Regex("^claude-(?:opus|sonnet|haiku|fable|mythos)-(\\d+)")
    private val GEMINI_MODEL = Regex("^gemini-(\\d+)")
    private val OPENAI_MODEL = Regex("^gpt-(\\d+)(?:\\.(\\d+))?")

    /** [model] で本文を分けずに1回で渡せる上限(文字数)。判断できないモデルは [FALLBACK_CHARS]。 */
    fun singleCallChars(provider: ApiProvider, model: String): Int {
        val id = model.trim().lowercase()
        return when (provider) {
            ApiProvider.CLAUDE -> {
                val major = CLAUDE_MODEL.find(id)?.groupValues?.get(1)?.toIntOrNull()
                when {
                    major == null -> FALLBACK_CHARS
                    major >= 5 -> LARGE_CHARS
                    major == 4 -> MEDIUM_CHARS
                    else -> FALLBACK_CHARS
                }
            }
            ApiProvider.GEMINI -> {
                val major = GEMINI_MODEL.find(id)?.groupValues?.get(1)?.toIntOrNull()
                if (major != null && major >= 2) LARGE_CHARS else FALLBACK_CHARS
            }
            ApiProvider.OPENAI -> {
                val match = OPENAI_MODEL.find(id)
                val major = match?.groupValues?.get(1)?.toIntOrNull()
                val minor = match?.groupValues?.get(2)?.toIntOrNull() ?: 0
                // GPT-4.1 以降。GPT-4o など、それ以前は文脈長が小さい
                if (major != null && (major >= 5 || (major == 4 && minor >= 1))) OPENAI_CHARS else FALLBACK_CHARS
            }
        }
    }

    /**
     * [model] の分割要約の設定。1回で渡せる上限を超える本文だけが分割される。
     * 分けるときの1部分は上限の 3 分の 1(最小 [FALLBACK_CHARS])にして、部分要約の1回あたりを重くしすぎない。
     * 呼び出しは部分の数 + 1 回で、部分は [PARALLELISM] 個ずつ並行する。
     * 時間の目安は WorkManager の実行時間の上限(約 10 分)に収まるようにする。
     */
    fun longTextConfig(provider: ApiProvider, model: String): LongTextConfig =
        longTextConfig(singleCallChars(provider, model))

    /** 文脈長を見積もれないとき・見積もりが外れたときの、従来の小さい単位の設定。 */
    fun fallbackConfig(): LongTextConfig = longTextConfig(FALLBACK_CHARS)

    private fun longTextConfig(singleCallChars: Int) = LongTextConfig(
        chunkChars = maxOf(FALLBACK_CHARS, singleCallChars / 3),
        // 上限が大きいモデルは部分も大きいため、間引く前に読める本文の量が従来(約 12 万文字)以上になる
        maxChunks = if (singleCallChars > FALLBACK_CHARS) 8 else 6,
        noteMaxChars = 1_200,
        timeBudgetMillis = 4 * 60 * 1000L,
        singleCallChars = singleCallChars,
        parallelism = PARALLELISM
    )
}
