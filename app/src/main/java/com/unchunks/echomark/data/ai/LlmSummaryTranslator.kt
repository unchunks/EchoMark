package com.unchunks.echomark.data.ai

import com.unchunks.echomark.domain.model.SummaryLanguage
import com.unchunks.echomark.domain.provider.SummaryTranslator
import com.unchunks.echomark.domain.repository.AiTask
import org.json.JSONObject
import javax.inject.Inject

/** 「要約」の設定の AI に、JSON で翻訳を返させる([AiPrompts.translateInstructions])。 */
class LlmSummaryTranslator @Inject constructor(
    private val resolver: LlmProviderResolver
) : SummaryTranslator {

    override suspend fun translate(text: String, target: SummaryLanguage): String {
        val response = resolver.resolve(AiTask.SUMMARY).complete(AiPrompts.translateInstructions(text, target))
        return parseTranslation(response) ?: error("翻訳を取り出せませんでした")
    }

    internal companion object {
        /** 応答の JSON(前後に余計な文字があってもよい)から `translation` を取り出す。空なら null。 */
        fun parseTranslation(response: String): String? {
            val start = response.indexOf('{')
            val end = response.lastIndexOf('}')
            if (start !in 0 until end) return null
            return try {
                JSONObject(response.substring(start, end + 1)).optString("translation").trim().takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
        }
    }
}
