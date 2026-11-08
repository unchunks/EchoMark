package com.unchunks.echomark.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.mapper.SmartFolderCodec
import com.unchunks.echomark.data.mapper.smartFolderEntity
import com.unchunks.echomark.data.mapper.toDomain
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkQuery
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.CreatedPeriod
import com.unchunks.echomark.domain.bookmark.model.ReadState
import com.unchunks.echomark.domain.bookmark.model.SmartFolderNames
import com.unchunks.echomark.domain.bookmark.model.SmartFolderResult
import com.unchunks.echomark.domain.bookmark.model.TagMatch
import com.unchunks.echomark.domain.bookmark.model.matches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** スマートフォルダ(#34)の保存・名前の決まり・条件の詰め方を、実 DB(インメモリ)で確かめる。 */
@RunWith(AndroidJUnit4::class)
class SmartFolderRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: SmartFolderRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = SmartFolderRepositoryImpl(db.smartFolderDao(), TestDispatcherProvider(Dispatchers.Unconfined))
    }

    @After
    fun tearDown() = db.close()

    private val everything = BookmarkQuery(
        filter = BookmarkFilter.FAVORITES,
        tagIds = setOf(3, 1),
        tagMatch = TagMatch.ANY,
        categories = setOf("技術", "旅行"),
        types = setOf(BookmarkType.PDF, BookmarkType.URL),
        period = CreatedPeriod.PAST_MONTH,
        readState = ReadState.UNREAD
    )

    private suspend fun folders() = repository.observeFolders().first()

    @Test
    fun 条件と並び順をそのまま保存して読み出せる() = runTest {
        val result = repository.create("  未読の資料  ", everything, BookmarkSortOrder.OLDEST)

        assertTrue(result is SmartFolderResult.Saved)
        val folder = folders().single()
        assertEquals("未読の資料", folder.name)
        assertEquals(everything, folder.query)
        assertEquals(BookmarkSortOrder.OLDEST, folder.sortOrder)
        assertTrue(folder.matches(everything))
        assertTrue(!folder.matches(everything.copy(readState = null)))
    }

    @Test
    fun 並び順を保存しなければ今の並び順のまま() = runTest {
        repository.create("全部", BookmarkQuery(), null)

        assertNull(folders().single().sortOrder)
    }

    @Test
    fun 作った順に並ぶ() = runTest {
        repository.create("い", BookmarkQuery(), null)
        repository.create("あ", BookmarkQuery(filter = BookmarkFilter.ARCHIVED), null)

        assertEquals(listOf("い", "あ"), folders().map { it.name })
    }

    @Test
    fun 名前が空や長すぎるものは保存しない() = runTest {
        assertEquals(SmartFolderResult.InvalidName, repository.create("   ", BookmarkQuery(), null))
        assertEquals(SmartFolderResult.InvalidName, repository.create("あ".repeat(SmartFolderNames.MAX_CHARS + 1), BookmarkQuery(), null))
        assertTrue(repository.create("あ".repeat(SmartFolderNames.MAX_CHARS), BookmarkQuery(), null) is SmartFolderResult.Saved)
        assertEquals(1, folders().size)
    }

    @Test
    fun 大文字小文字と全角半角が違うだけの同じ名前は作れない() = runTest {
        repository.create("Kotlin 記事", BookmarkQuery(), null)

        assertEquals(SmartFolderResult.NameTaken, repository.create("kotlin 記事", BookmarkQuery(), null))
        assertEquals(SmartFolderResult.NameTaken, repository.create("ＫＯＴＬＩＮ 記事", BookmarkQuery(), null))
        assertEquals(1, folders().size)
    }

    @Test
    fun 名前を変える_同じ名前や自分の名前のままの変更を扱う() = runTest {
        val first = (repository.create("一つ目", BookmarkQuery(), null) as SmartFolderResult.Saved).id
        repository.create("二つ目", BookmarkQuery(), null)

        assertEquals(SmartFolderResult.NameTaken, repository.rename(first, "二つ目"))
        assertEquals(SmartFolderResult.InvalidName, repository.rename(first, " "))
        // 自分の名前を大文字小文字だけ変えるのはよい
        assertTrue(repository.rename(first, "一つ目 ") is SmartFolderResult.Saved)
        assertTrue(repository.rename(first, "新しい名前") is SmartFolderResult.Saved)
        assertEquals(listOf("新しい名前", "二つ目"), folders().map { it.name })
    }

    @Test
    fun 条件を置き換えると名前は変わらない() = runTest {
        val id = (repository.create("フォルダ", BookmarkQuery(readState = ReadState.UNREAD), BookmarkSortOrder.TITLE) as SmartFolderResult.Saved).id

        repository.updateConditions(id, everything, null)

        val folder = folders().single()
        assertEquals("フォルダ", folder.name)
        assertEquals(everything, folder.query)
        assertNull(folder.sortOrder)
    }

    @Test
    fun 削除するとそのフォルダだけ消える() = runTest {
        val first = (repository.create("残す", BookmarkQuery(), null) as SmartFolderResult.Saved).id
        val second = (repository.create("消す", BookmarkQuery(), null) as SmartFolderResult.Saved).id

        repository.delete(second)

        assertEquals(listOf(first), folders().map { it.id })
    }

    @Test
    fun 読めない値は項目ごとに既定へ戻し_数値でないタグIDは読み飛ばす() {
        val entity = smartFolderEntity("壊れた", everything, BookmarkSortOrder.TITLE, createdAt = 1).copy(
            filter = "SOMETHING_NEW",
            tagIds = "5,x,,7",
            tagMatch = "WEIRD",
            types = "PDF,HOLOGRAM,URL",
            period = "PAST_DECADE",
            readState = "HALF",
            sortOrder = "BY_MOOD"
        )

        val folder = entity.toDomain()

        assertEquals(BookmarkFilter.ACTIVE, folder.query.filter)
        assertEquals(setOf(5L, 7L), folder.query.tagIds)
        assertEquals(TagMatch.ALL, folder.query.tagMatch)
        assertEquals(setOf(BookmarkType.PDF, BookmarkType.URL), folder.query.types)
        assertNull(folder.query.period)
        assertNull(folder.query.readState)
        assertNull(folder.sortOrder)
    }

    @Test
    fun 複数の値の詰め方は順序に依らず同じ形になる() {
        assertEquals("1,3", SmartFolderCodec.encodeIds(setOf(3, 1)))
        assertEquals("URL,PDF", SmartFolderCodec.encodeTypes(setOf(BookmarkType.PDF, BookmarkType.URL)))
        assertEquals("技術\n旅行", SmartFolderCodec.encodeCategories(setOf("旅行", "技術")))
        assertEquals(setOf("改行 入り"), SmartFolderCodec.decodeCategories(SmartFolderCodec.encodeCategories(setOf("改行\n入り"))))
        assertEquals(emptySet<Long>(), SmartFolderCodec.decodeIds(""))
    }
}
