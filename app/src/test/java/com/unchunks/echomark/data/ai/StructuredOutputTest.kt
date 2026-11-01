package com.unchunks.echomark.data.ai

import com.unchunks.echomark.domain.model.AnalysisScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 解析の出力の JSON スキーマと、形の確かめ・作り直し。 */
class StructuredOutputTest {

    @Test
    fun スキーマは作る項目だけを全部requiredにし余計な項目を許さない() {
        fun schemaOf(scope: AnalysisScope) = JSONObject(AnalysisSchema.analysis(scope))
        for (scope in AnalysisScope.entries) {
            val schema = schemaOf(scope)
            assertEquals("object", schema.getString("type"))
            assertFalse(schema.getBoolean("additionalProperties"))
            val required = schema.getJSONArray("required").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            assertEquals(AnalysisSchema.fields(scope).toSet(), required)
            assertEquals(required, schema.getJSONObject("properties").keys().asSequence().toSet())
        }
        assertEquals(setOf("summary"), schemaOf(AnalysisScope.SUMMARY).getJSONObject("properties").keys().asSequence().toSet())
        assertEquals(setOf("tags", "category"), schemaOf(AnalysisScope.TAGS).getJSONObject("properties").keys().asSequence().toSet())
    }

    @Test
    fun クラウド向けは長さと数の制約を書かず端末内向けは書く() {
        val cloud = AnalysisSchema.analysis(AnalysisScope.ALL)
        assertFalse(cloud, cloud.contains("maxLength") || cloud.contains("maxItems"))

        val local = JSONObject(AnalysisSchema.analysis(AnalysisScope.ALL, AnalysisSchema.Limits(summaryMaxChars = 450)))
        val properties = local.getJSONObject("properties")
        assertEquals(450, properties.getJSONObject("summary").getInt("maxLength"))
        assertEquals(AiPrompts.MAX_TAGS, properties.getJSONObject("tags").getInt("maxItems"))
        assertTrue(properties.getJSONObject("tags").getJSONObject("items").has("maxLength"))
        assertEquals(900, JSONObject(AnalysisSchema.notes(900)).getJSONObject("properties").getJSONObject("notes").getInt("maxLength"))
    }

    @Test
    fun 正しい出力は前後に余計な文字があっても通す() {
        assertNull(StructuredOutput.problemOf("""{"summary":"要約","tags":["a"],"category":"技術"}""", AnalysisScope.ALL))
        assertNull(StructuredOutput.problemOf("```json\n{\"summary\":\"要約\",\"tags\":[],\"category\":\"技術\"}\n```", AnalysisScope.ALL))
        assertNull(StructuredOutput.problemOf("""{"summary":"要約"}""", AnalysisScope.SUMMARY))
        assertNull(StructuredOutput.problemOf("""{"tags":["Kotlin"],"category":"技術"}""", AnalysisScope.TAGS))
    }

    @Test
    fun 形の崩れた出力は理由を返す() {
        assertEquals("JSON のオブジェクトになっていません", StructuredOutput.problemOf("要約: よくわからない", AnalysisScope.ALL))
        assertEquals("JSON のオブジェクトになっていません", StructuredOutput.problemOf("""{"summary":"途中で""", AnalysisScope.ALL))
        assertEquals("tags がありません", StructuredOutput.problemOf("""{"summary":"要約","category":"技術"}""", AnalysisScope.ALL))
        assertEquals("summary が空です", StructuredOutput.problemOf("""{"summary":" ","tags":[],"category":"技術"}""", AnalysisScope.ALL))
        assertEquals("tags が配列になっていません", StructuredOutput.problemOf("""{"tags":"a, b","category":"技術"}""", AnalysisScope.TAGS))
        assertEquals("tags に文字列でない値があります", StructuredOutput.problemOf("""{"tags":[1],"category":"技術"}""", AnalysisScope.TAGS))
        assertEquals("category が文字列になっていません", StructuredOutput.problemOf("""{"tags":[],"category":null}""", AnalysisScope.TAGS))
    }

    @Test
    fun 正しい出力が出たらすぐ返し作り直しには理由と前の出力を渡す() = runBlocking {
        val calls = mutableListOf<Pair<String?, String?>>()
        val outputs = ArrayDeque(listOf("壊れた", """{"summary":"直した"}"""))

        val result = StructuredOutput.generateWithRepair(
            maxRepairs = 2,
            label = "test",
            validate = { StructuredOutput.problemOf(it, AnalysisScope.SUMMARY) }
        ) { problem, previous ->
            calls += problem to previous
            outputs.removeFirst()
        }

        assertEquals("""{"summary":"直した"}""", result)
        assertEquals(listOf(null to null, "JSON のオブジェクトになっていません" to "壊れた"), calls)
    }

    @Test
    fun 作り直しは上限の回数で止めて最後の出力を返す() = runBlocking {
        var count = 0
        val result = StructuredOutput.generateWithRepair(maxRepairs = 1, label = "test", validate = { "だめ" }) { _, _ ->
            count++
            "出力$count"
        }
        assertEquals(2, count)
        assertEquals("出力2", result)
    }

    @Test
    fun 作り直しの指示は作る項目の形を見せる() {
        val summaryOnly = AiPrompts.repairInstructions("summary が空です", AnalysisScope.SUMMARY)
        assertTrue(summaryOnly, summaryOnly.contains("summary が空です"))
        assertTrue(summaryOnly.contains("\"summary\""))
        assertFalse(summaryOnly.contains("\"tags\""))
        assertTrue(AiPrompts.repairInstructions("x", AnalysisScope.TAGS).contains("\"category\""))
    }
}
