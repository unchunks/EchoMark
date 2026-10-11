package com.unchunks.echomark.data.local.objectbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.testing.embeddingBox
import com.unchunks.echomark.testing.inMemoryBoxStore
import io.objectbox.BoxStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 近傍検索が、指定した埋め込みモデルの版のベクトルだけを対象にするか(JVM 上の ObjectBox)。 */
@RunWith(AndroidJUnit4::class)
class VectorSearchDataSourceTest {

    private lateinit var boxStore: BoxStore
    private lateinit var dataSource: VectorSearchDataSource

    /** x 軸から [tilt] だけ傾けたベクトル。傾きが大きいほど (1,0,...) から遠い。 */
    private fun vectorOf(tilt: Float) = FloatArray(768) {
        when (it) {
            0 -> 1f
            1 -> tilt
            else -> 0f
        }
    }

    @Before
    fun setUp() {
        boxStore = inMemoryBoxStore()
        dataSource = VectorSearchDataSource(boxStore.embeddingBox())
    }

    @After
    fun tearDown() {
        boxStore.close()
    }

    @Test
    fun 指定した版のベクトルだけが近傍に出る() {
        dataSource.upsert(1L, vectorOf(0f), "old")
        dataSource.upsert(2L, vectorOf(0.5f), "new")

        val hits = dataSource.nearestNeighbors(vectorOf(0f), limit = 5, modelVersion = "new")

        assertEquals(listOf(2L), hits.map { it.bookmarkId })
    }

    @Test
    fun 旧版のベクトルが近傍の上位を占めても新しい版を取りこぼさない() {
        // 検索ベクトルに近い順に旧版が5件、遠い側に新しい版が2件
        for (i in 1L..5L) dataSource.upsert(i, vectorOf(0.01f * i), "old")
        dataSource.upsert(10L, vectorOf(1f), "new")
        dataSource.upsert(11L, vectorOf(2f), "new")

        val hits = dataSource.nearestNeighbors(vectorOf(0f), limit = 2, modelVersion = "new")

        // 近い順
        assertEquals(listOf(10L, 11L), hits.map { it.bookmarkId })
    }

    @Test
    fun 結果は近い順で_limit件に切り詰める() {
        dataSource.upsert(1L, vectorOf(2f), "v")
        dataSource.upsert(2L, vectorOf(0.1f), "v")
        dataSource.upsert(3L, vectorOf(1f), "v")

        val hits = dataSource.nearestNeighbors(vectorOf(0f), limit = 2, modelVersion = "v")

        assertEquals(listOf(2L, 3L), hits.map { it.bookmarkId })
    }

    @Test
    fun 一致する版が無ければ空() {
        dataSource.upsert(1L, vectorOf(0f), "old")

        assertEquals(emptyList<VectorSearchResult>(), dataSource.nearestNeighbors(vectorOf(0f), 5, "new"))
    }

    @Test
    fun 版ごとの件数を数える() {
        dataSource.upsert(1L, vectorOf(0f), "a")
        dataSource.upsert(2L, vectorOf(0.1f), "a")
        dataSource.upsert(3L, vectorOf(0.2f), "b")

        assertEquals(2L, dataSource.countByModelVersion("a"))
        assertEquals(1L, dataSource.countByModelVersion("b"))
        assertEquals(0L, dataSource.countByModelVersion("c"))
    }
}
