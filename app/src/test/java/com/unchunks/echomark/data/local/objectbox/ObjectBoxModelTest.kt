package com.unchunks.echomark.data.local.objectbox

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ObjectBox のモデル(app/objectbox-models/default.json)の UID が、端末にある既存の DB と食い違わないか。
 *
 * ObjectBox はエンティティ・プロパティを名前ではなく UID で見分ける。default.json を消して作り直すと UID が振り直され、
 * 既存の端末では DB を開けなくなる。プロパティの名前を @Uid なしで変えると別のプロパティとして扱われ、その値は消える。
 * 一度使った UID は、モデルに残っているか、意図して消した(retired*Uids に移った)かのどちらかでなければならない。
 * 手順は docs/database-migrations.md。
 */
class ObjectBoxModelTest {

    @Test
    fun 使ってきたUIDがモデルに残っているか意図して消したものになっている() {
        val model = JSONObject(modelFile().readText())
        val current = buildSet {
            model.getJSONArray("entities").objects().forEach { entity ->
                add(uidOf(entity.getString("id")))
                entity.getJSONArray("properties").objects().forEach { property ->
                    add(uidOf(property.getString("id")))
                    property.optString("indexId").takeIf { it.isNotEmpty() }?.let { add(uidOf(it)) }
                }
            }
        }
        val retired = listOf("retiredEntityUids", "retiredIndexUids", "retiredPropertyUids").flatMapTo(mutableSetOf()) { key ->
            model.getJSONArray(key).let { array -> (0 until array.length()).map { array.getLong(it) } }
        }

        val lost = KNOWN_UIDS.filterKeys { it !in current && it !in retired }
        assertTrue(
            "default.json から UID が消えた(作り直した・名前を変えた?): $lost。git の default.json に戻し、" +
                "名前を変えるときは @Uid で元の UID を指定する",
            lost.isEmpty()
        )
    }

    private fun modelFile(): File =
        // Gradle は app/ を作業ディレクトリにして実行する。IDE からリポジトリのルートで実行したときにも探す
        listOf(File("objectbox-models/default.json"), File("app/objectbox-models/default.json"))
            .first { it.isFile }

    private fun uidOf(id: String): Long = id.substringAfter(':').toLong()

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private companion object {
        /** これまでに使った UID(端末の DB に記録されている)。モデルにエンティティ・プロパティを足したら、ここにも足す。 */
        val KNOWN_UIDS: Map<Long, String> = mapOf(
            4702108779896928864L to "EmbeddingEntity",
            1538455150627927963L to "EmbeddingEntity.id",
            513688742399234277L to "EmbeddingEntity.bookmarkId",
            2786043579742340486L to "EmbeddingEntity.vector",
            3397500932943126734L to "EmbeddingEntity.modelVersion",
            6270450334359875796L to "EmbeddingEntity.vector の HNSW インデックス"
        )
    }
}
