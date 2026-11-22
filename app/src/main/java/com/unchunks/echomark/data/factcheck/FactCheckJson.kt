package com.unchunks.echomark.data.factcheck

import com.unchunks.echomark.domain.factcheck.CheckedClaim
import com.unchunks.echomark.domain.factcheck.ClaimVerdict
import com.unchunks.echomark.domain.factcheck.FactCheckEvidence
import com.unchunks.echomark.domain.websearch.WebSourceText
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 確かめた主張([CheckedClaim])を、DB の列(fact_checks.claims)に入れる JSON の配列にする。
 * 例: `[{"claim":"…","verdict":"SUPPORTED","evidence":[{"url":"…","title":"…","domain":"…","quote":"…"}]}]`
 */
object FactCheckJson {
    fun encode(claims: List<CheckedClaim>): String = JSONArray().apply {
        claims.forEach { claim ->
            put(
                JSONObject()
                    .put("claim", claim.claim)
                    .put("verdict", claim.verdict.name)
                    .put(
                        "evidence",
                        JSONArray().apply {
                            claim.evidence.forEach { e ->
                                put(JSONObject().put("url", e.url).put("title", e.title).put("domain", e.domain).put("quote", e.quote))
                            }
                        }
                    )
            )
        }
    }.toString()

    /** 読めない・形のおかしいもの(知らない判定・http/https 以外の URL)は除く */
    fun decode(json: String?): List<CheckedClaim> {
        if (json.isNullOrBlank()) return emptyList()
        val array = try {
            JSONArray(json)
        } catch (e: JSONException) {
            return emptyList()
        }
        return (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val claim = entry.optString("claim").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val verdict = ClaimVerdict.entries.firstOrNull { it.name == entry.optString("verdict") } ?: return@mapNotNull null
            val evidence = entry.optJSONArray("evidence")?.let { list ->
                (0 until list.length()).mapNotNull { i ->
                    val e = list.optJSONObject(i) ?: return@mapNotNull null
                    val url = e.optString("url")
                    val domain = WebSourceText.domainOf(url) ?: return@mapNotNull null
                    FactCheckEvidence(url, e.optString("title").ifBlank { domain }, e.optString("domain").ifBlank { domain }, e.optString("quote"))
                }
            }.orEmpty()
            CheckedClaim(claim, verdict, evidence)
        }
    }
}
