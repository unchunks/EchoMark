package com.unchunks.echomark.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unchunks.echomark.di.applyMigrationPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * マイグレーションの抜け漏れを、版を上げるたびに書き足さなくても見つけるテスト(Robolectric)。
 * 個々のマイグレーションでの値の埋め方は [MigrationTest] で確かめる。
 *
 * - [AppDatabase.VERSION] を上げたのに、スキーマ JSON をコミットしていない・マイグレーションを足していない
 * - エンティティを変えたのに版を上げていない(書き出し直された最新のスキーマと、マイグレーション後の DB が食い違う)
 * - マイグレーションでテーブル・列・値が消える
 *
 * のどれでも失敗する。手順は docs/database-migrations.md。
 */
@RunWith(AndroidJUnit4::class)
class MigrationPathTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Test
    fun 対応する全部の版のスキーマをコミットしている() {
        val versions = OLDEST_MIGRATABLE_VERSION..AppDatabase.VERSION
        val missing = versions.filter { runCatching { loadSchema(it) }.isFailure }
        assertEquals(
            "スキーマ JSON($SCHEMA_DIR/<版>.json)が無い版。exportSchema を外していないか確かめ、ビルドで書き出された JSON をコミットする",
            emptyList<Int>(),
            missing
        )
        versions.forEach { assertEquals("$it.json の版", it, loadSchema(it).getInt("version")) }

        val newest = context.assets.list(SCHEMA_FOLDER).orEmpty()
            .mapNotNull { it.removeSuffix(".json").toIntOrNull() }
            .max()
        assertEquals("AppDatabase.VERSION より新しいスキーマ JSON がある", AppDatabase.VERSION, newest)
    }

    @Test
    fun マイグレーションは対応する版の範囲に収まっている() {
        ALL_MIGRATIONS.forEach { migration ->
            val label = "MIGRATION_${migration.startVersion}_${migration.endVersion}"
            assertTrue("$label: 古い版へ戻すマイグレーションは置かない", migration.startVersion < migration.endVersion)
            assertTrue(
                "$label: 開発初期の版(作り直す版)からのマイグレーションは置かない",
                migration.startVersion >= OLDEST_MIGRATABLE_VERSION
            )
            assertTrue(
                "$label: AppDatabase.VERSION(${AppDatabase.VERSION})を上げていない",
                migration.endVersion <= AppDatabase.VERSION
            )
        }
    }

    @Test
    fun 各版のDBをアプリと同じ方針で現在の版まで上げてもデータを失わない() {
        val failures = startVersions().mapNotNull { start ->
            runCatching { migrateWithAppPolicy(start) }.exceptionOrNull()?.let { "v$start から: $it" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun 各版のDBを現在の版まで上げるとスキーマJSONと一致しデータを失わない() {
        val failures = startVersions().mapNotNull { start ->
            runCatching { migrateAndValidateAgainstSchema(start) }.exceptionOrNull()?.let { "v$start から: $it" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun startVersions() = OLDEST_MIGRATABLE_VERSION until AppDatabase.VERSION

    /** アプリ(DatabaseModule)と同じ方針で開く。Room がエンティティとの一致も検証する。 */
    private fun migrateWithAppPolicy(start: Int) {
        val name = "migration-path-app-$start.db"
        val before = helperFor(name).createDatabase(start).use { it.insertOneRowPerTable(loadSchema(start)) }

        var destructiveCalls = 0
        val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .applyMigrationPolicy(allowDestructiveDowngrade = false) { destructiveCalls++ }
            .build()
        try {
            // 開いた時点でマイグレーションと検証が走る
            val db = room.openHelper.writableDatabase
            assertEquals("DB を作り直した", 0, destructiveCalls)
            assertDataKept(before) { sql ->
                db.query(sql).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) })
                    }
                }
            }
        } finally {
            room.close()
        }
    }

    /** MigrationTestHelper でコミットしたスキーマ JSON(最新の版)と比べる。 */
    private fun migrateAndValidateAgainstSchema(start: Int) {
        val helper = helperFor("migration-path-schema-$start.db")
        val before = helper.createDatabase(start).use { it.insertOneRowPerTable(loadSchema(start)) }

        helper.runMigrationsAndValidate(AppDatabase.VERSION, ALL_MIGRATIONS.toList()).use { connection ->
            assertDataKept(before) { sql -> connection.readRows(sql) }
        }
    }

    /**
     * マイグレーション前の各テーブルの行が、最新の版にも残っているか。
     * 最新の版にもある列は値が変わっていないこと、無くなったテーブル・列は [INTENTIONAL_DATA_CHANGES] に載っていることを確かめる。
     */
    private fun assertDataKept(before: Map<String, Map<String, String?>>, query: (String) -> List<List<String?>>) {
        val latest = tablesOf(loadSchema(AppDatabase.VERSION)).associateBy { it.name }
        before.forEach { (table, values) ->
            val latestTable = latest[table]
            if (latestTable == null) {
                assertTrue("テーブル $table が無くなった", table in INTENTIONAL_DATA_CHANGES)
                return@forEach
            }
            val columns = values.keys.filter { it in latestTable.columnNames }
            values.keys.filterNot { it in latestTable.columnNames }.forEach { column ->
                assertTrue("列 $table.$column が無くなった", "$table.$column" in INTENTIONAL_DATA_CHANGES)
            }
            val rows = query("SELECT ${columns.joinToString { "quote(`$it`)" }} FROM `$table`")
            assertEquals("$table の行数", 1, rows.size)
            columns.forEachIndexed { i, column ->
                if ("$table.$column" in INTENTIONAL_DATA_CHANGES) return@forEachIndexed
                assertEquals("$table.$column の値", values[column], rows.single()[i])
            }
        }
    }

    private fun helperFor(fileName: String) = MigrationTestHelper(
        instrumentation = instrumentation,
        // Robolectric のテストごとの一時データディレクトリに作る
        file = context.getDatabasePath(fileName),
        driver = AndroidSQLiteDriver(),
        databaseClass = AppDatabase::class
    )

    private fun loadSchema(version: Int): JSONObject =
        context.assets.open("$SCHEMA_FOLDER/$version.json").bufferedReader().use {
            JSONObject(it.readText()).getJSONObject("database")
        }

    private class TableSchema(
        val name: String,
        /** 列名と型(affinity) */
        val columns: List<Pair<String, String>>,
        /** 主キー・外部キーの列。値を 1 にそろえて、親テーブルの行を参照させる */
        val keyColumns: Set<String>,
        /** 外部キーで参照するテーブル */
        val parents: Set<String>
    ) {
        val columnNames: Set<String> get() = columns.mapTo(mutableSetOf()) { it.first }
    }

    private fun tablesOf(schema: JSONObject): List<TableSchema> {
        val entities = schema.getJSONArray("entities")
        return (0 until entities.length()).map { i ->
            val entity = entities.getJSONObject(i)
            val fields = entity.getJSONArray("fields")
            val foreignKeys = entity.optJSONArray("foreignKeys")
            val fkObjects = (0 until (foreignKeys?.length() ?: 0)).map { foreignKeys!!.getJSONObject(it) }
            val pk = entity.getJSONObject("primaryKey").getJSONArray("columnNames")
            TableSchema(
                name = entity.getString("tableName"),
                columns = (0 until fields.length()).map {
                    val field = fields.getJSONObject(it)
                    field.getString("columnName") to field.getString("affinity")
                },
                keyColumns = (0 until pk.length()).map { pk.getString(it) }.toSet() +
                    fkObjects.flatMap { fk -> fk.getJSONArray("columns").let { c -> (0 until c.length()).map(c::getString) } },
                parents = fkObjects.map { it.getString("table") }.toSet()
            )
        }
    }

    /**
     * スキーマ JSON の各テーブルに、すべての列に値の入った行を 1 件ずつ入れ、入れた値(quote した文字列)を返す。
     * 列ごとに違う値にして、マイグレーションで列が入れ替わっても分かるようにする。
     */
    private fun SQLiteConnection.insertOneRowPerTable(schema: JSONObject): Map<String, Map<String, String?>> {
        val tables = tablesOf(schema)
        val inserted = linkedMapOf<String, Map<String, String?>>()
        while (inserted.size < tables.size) {
            // 外部キーの親テーブルから入れる
            val table = tables.first { t -> t.name !in inserted && t.parents.all { it in inserted || it == t.name } }
            val values = table.columns.mapIndexed { i, (column, affinity) ->
                when {
                    column in table.keyColumns -> "1"
                    affinity == "INTEGER" -> "${100 + i}"
                    affinity == "REAL" -> "$i.5"
                    affinity == "BLOB" -> "X'${"%02X".format(i)}'"
                    else -> "'${table.name}.$column'"
                }
            }
            execSQL(
                "INSERT INTO `${table.name}` (${table.columns.joinToString { "`${it.first}`" }}) " +
                    "VALUES (${values.joinToString()})"
            )
            val row = readRows("SELECT ${table.columns.joinToString { "quote(`${it.first}`)" }} FROM `${table.name}`")
                .single()
            inserted[table.name] = table.columns.map { it.first }.zip(row).toMap()
        }
        return inserted
    }

    private fun SQLiteConnection.readRows(sql: String): List<List<String?>> = prepare(sql).use { stmt ->
        buildList {
            while (stmt.step()) {
                add((0 until stmt.getColumnCount()).map { if (stmt.isNull(it)) null else stmt.getText(it) })
            }
        }
    }

    private companion object {
        val SCHEMA_FOLDER: String = AppDatabase::class.java.name
        const val SCHEMA_DIR = "app/schemas/com.unchunks.echomark.data.local.AppDatabase"

        /**
         * マイグレーションで意図して消す・値を変えるテーブル(`table`)と列(`table.column`)。
         * ここに載せたものだけ、[assertDataKept] で消えていても・値が変わっていても失敗にしない。
         * 載せるときは、消える理由をマイグレーションの KDoc に書く。
         */
        val INTENTIONAL_DATA_CHANGES: Set<String> = emptySet()
    }
}
