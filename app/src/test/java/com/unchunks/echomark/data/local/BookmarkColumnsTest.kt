package com.unchunks.echomark.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.data.local.dao.BOOKMARK_COLUMNS_WITHOUT_CONTENT
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** 本文を読まない問い合わせの列の並び([BOOKMARK_COLUMNS_WITHOUT_CONTENT])が、bookmarks の列と食い違っていないか */
@RunWith(AndroidJUnit4::class)
class BookmarkColumnsTest {

    @Test
    fun 本文を読まない列の並びはbookmarksの列をすべて含む() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val tableColumns = db.openHelper.readableDatabase.query("PRAGMA table_info(bookmarks)").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            // 「NULL AS content」は content の列として数える
            val listed = BOOKMARK_COLUMNS_WITHOUT_CONTENT.split(',').map { it.trim().substringAfterLast(' ') }

            assertEquals(tableColumns.sorted(), listed.sorted())
        } finally {
            db.close()
        }
    }
}
