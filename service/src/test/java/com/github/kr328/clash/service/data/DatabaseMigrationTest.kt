package com.github.kr328.clash.service.data

import android.content.ContextWrapper
import android.database.Cursor
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import com.github.kr328.clash.service.data.migrations.MIGRATIONS
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Room migrations against a real SQLite (xerial JDBC behind a [SupportSQLiteDatabase] adapter), with
 * Room's own generated schema check: [SupportSQLiteOpenHelper.Callback.onUpgrade] of the generated
 * `Database_Impl` runs the migration path and fails with "Migration didn't properly handle" when
 * the migrated tables differ from the entities.
 */
class DatabaseMigrationTest {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    private val db = jdbcDatabase(connection)

    @AfterTest
    fun close() {
        connection.close()
    }

    @Test
    fun migration_3_4_adds_desync_settings_and_keeps_rows() {
        V3_SCHEMA.forEach { db.execSQL(it) }
        db.execSQL(
            "INSERT INTO imported VALUES ('11111111-1111-1111-1111-111111111111', 'Sub', 'Url', " +
                "'https://example.com/sub', 0, 1, 2, 3, 4, 5, 6, 'AGE-SECRET-KEY-1')",
        )
        db.execSQL(
            "INSERT INTO pending VALUES ('22222222-2222-2222-2222-222222222222', 'Local', 'File', " +
                "'', 0, 0, 0, 0, 0, 7, 8, NULL)",
        )
        db.execSQL(
            "INSERT INTO selections VALUES ('11111111-1111-1111-1111-111111111111', 'GLOBAL', 'node')",
        )

        // Room's generated helper: migrates 3 -> 4 and validates every table against the entities.
        openCallback().onUpgrade(db, 3, 4)

        val imported = rows("SELECT name, type, source, profileOrder, ageSecretKey, desyncSettings FROM imported")
        assertEquals(listOf(listOf<Any?>("Sub", "Url", "https://example.com/sub", 6, "AGE-SECRET-KEY-1", null)), imported)
        val pending = rows("SELECT name, createdAt, profileOrder, desyncSettings FROM pending")
        assertEquals(listOf(listOf<Any?>("Local", 7, 8, null)), pending)
        assertEquals(1, rows("SELECT * FROM selections").size)

        db.execSQL("UPDATE imported SET desyncSettings = '{\"services\":[\"youtube\"]}'")
        assertEquals("{\"services\":[\"youtube\"]}", rows("SELECT desyncSettings FROM imported").single().single())
    }

    @Test
    fun migration_path_from_1_reaches_4() {
        V1_SCHEMA.forEach { db.execSQL(it) }
        db.execSQL(
            "INSERT INTO imported VALUES ('11111111-1111-1111-1111-111111111111', 'Sub', 'Url', " +
                "'https://example.com/sub', 0, 0, 0, 0, 0, 42)",
        )

        openCallback().onUpgrade(db, 1, 4)

        val row = rows("SELECT profileOrder, ageSecretKey, desyncSettings FROM imported").single()
        assertEquals(listOf<Any?>(42, null, null), row)
    }

    @Test
    fun fresh_schema_has_the_desync_column() {
        openCallback().onCreate(db)

        for (table in listOf("imported", "pending")) {
            // cid, name, type, notnull, dflt_value, pk
            val column = rows("PRAGMA table_info($table)").singleOrNull { it[1] == "desyncSettings" }
            assertEquals(listOf<Any?>("TEXT", 0), column?.subList(2, 4), "$table.desyncSettings")
        }
    }

    private fun rows(sql: String): List<List<Any?>> {
        connection.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val n = rs.metaData.columnCount
                val out = mutableListOf<List<Any?>>()
                while (rs.next()) out += (1..n).map { rs.getObject(it) }
                return out
            }
        }
    }

    /** The RoomOpenHelper of the generated `Database_Impl`, captured through the open-helper factory. */
    private fun openCallback(): SupportSQLiteOpenHelper.Callback {
        var captured: SupportSQLiteOpenHelper.Configuration? = null
        val factory = SupportSQLiteOpenHelper.Factory { configuration ->
            captured = configuration
            stub(SupportSQLiteOpenHelper::class.java) { _, _ -> null }
        }
        Room.databaseBuilder(ContextWrapper(null), Database::class.java, "migration-test")
            .addMigrations(*MIGRATIONS)
            .openHelperFactory(factory)
            .build()
        return checkNotNull(captured).callback
    }

    private companion object {
        val V1_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `imported` (`uuid` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `source` TEXT NOT NULL, `interval` INTEGER NOT NULL, `upload` INTEGER NOT NULL, `download` INTEGER NOT NULL, `total` INTEGER NOT NULL, `expire` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`uuid`))",
            "CREATE TABLE IF NOT EXISTS `pending` (`uuid` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `source` TEXT NOT NULL, `interval` INTEGER NOT NULL, `upload` INTEGER NOT NULL, `download` INTEGER NOT NULL, `total` INTEGER NOT NULL, `expire` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`uuid`))",
            "CREATE TABLE IF NOT EXISTS `selections` (`uuid` TEXT NOT NULL, `proxy` TEXT NOT NULL, `selected` TEXT NOT NULL, PRIMARY KEY(`uuid`, `proxy`), FOREIGN KEY(`uuid`) REFERENCES `imported`(`uuid`) ON UPDATE CASCADE ON DELETE CASCADE )",
        )

        /** Database_Impl.createAllTables of version 3 (before desyncSettings). */
        val V3_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `imported` (`uuid` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `source` TEXT NOT NULL, `interval` INTEGER NOT NULL, `upload` INTEGER NOT NULL, `download` INTEGER NOT NULL, `total` INTEGER NOT NULL, `expire` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `profileOrder` INTEGER NOT NULL, `ageSecretKey` TEXT, PRIMARY KEY(`uuid`))",
            "CREATE TABLE IF NOT EXISTS `pending` (`uuid` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `source` TEXT NOT NULL, `interval` INTEGER NOT NULL, `upload` INTEGER NOT NULL, `download` INTEGER NOT NULL, `total` INTEGER NOT NULL, `expire` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `profileOrder` INTEGER NOT NULL, `ageSecretKey` TEXT, PRIMARY KEY(`uuid`))",
            "CREATE TABLE IF NOT EXISTS `selections` (`uuid` TEXT NOT NULL, `proxy` TEXT NOT NULL, `selected` TEXT NOT NULL, PRIMARY KEY(`uuid`, `proxy`), FOREIGN KEY(`uuid`) REFERENCES `imported`(`uuid`) ON UPDATE CASCADE ON DELETE CASCADE )",
        )

        @Suppress("UNCHECKED_CAST")
        fun <T> stub(type: Class<T>, handler: (Method, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(type)
                    "equals" -> false
                    "toString" -> type.simpleName
                    else -> handler(method, args ?: emptyArray()) ?: defaultValue(method.returnType)
                }
            } as T

        fun defaultValue(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> 0.toChar()
            else -> null
        }

        /** The calls Room's open helper and migrations make, on a JDBC connection. */
        fun jdbcDatabase(connection: Connection): SupportSQLiteDatabase =
            stub(SupportSQLiteDatabase::class.java) { method, args ->
                when (method.name) {
                    "execSQL" -> {
                        connection.prepareStatement(args[0] as String).use { st ->
                            (args.getOrNull(1) as? Array<*>)?.forEachIndexed { i, v -> st.setObject(i + 1, v) }
                            st.execute()
                        }
                        null
                    }
                    "query" -> when (val q = args[0]) {
                        is String -> query(connection, q, (args.getOrNull(1) as? Array<*>)?.toList().orEmpty())
                        is SupportSQLiteQuery -> {
                            val bound = sortedMapOf<Int, Any?>()
                            q.bindTo(
                                stub(SupportSQLiteProgram::class.java) { m, a ->
                                    if (m.name.startsWith("bind") && a.isNotEmpty()) {
                                        bound[a[0] as Int] = a.getOrNull(1)
                                    }
                                    null
                                },
                            )
                            query(connection, q.sql, bound.values.toList())
                        }
                        else -> error("query($q)")
                    }
                    "isOpen" -> true
                    "getVersion" -> query(connection, "PRAGMA user_version", emptyList()).let {
                        it.moveToFirst()
                        it.getInt(0)
                    }
                    "setVersion" -> {
                        connection.createStatement().use { it.execute("PRAGMA user_version = ${args[0]}") }
                        null
                    }
                    else -> null
                }
            }

        fun query(connection: Connection, sql: String, args: List<Any?>): Cursor {
            val columns = mutableListOf<String>()
            val rows = mutableListOf<List<Any?>>()
            connection.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, v -> st.setObject(i + 1, v) }
                if (st.execute()) {
                    st.resultSet.use { rs ->
                        val n = rs.metaData.columnCount
                        for (i in 1..n) columns += rs.metaData.getColumnLabel(i)
                        while (rs.next()) rows += (1..n).map { rs.getObject(it) }
                    }
                }
            }
            var position = -1
            return stub(Cursor::class.java) { method, a ->
                fun value(i: Int) = rows[position][i]
                when (method.name) {
                    "getCount" -> rows.size
                    "getPosition" -> position
                    "moveToNext" -> (position + 1 < rows.size).also { if (it) position++ else position = rows.size }
                    "moveToFirst" -> rows.isNotEmpty().also { position = 0 }
                    "moveToPosition" -> ((a[0] as Int) in rows.indices).also { position = a[0] as Int }
                    "isAfterLast" -> position >= rows.size
                    "getColumnCount" -> columns.size
                    "getColumnNames" -> columns.toTypedArray()
                    "getColumnName" -> columns[a[0] as Int]
                    "getColumnIndex" -> columns.indexOf(a[0] as String)
                    "getColumnIndexOrThrow" -> columns.indexOf(a[0] as String).also {
                        require(it >= 0) { "no column ${a[0]}" }
                    }
                    "isNull" -> value(a[0] as Int) == null
                    "getString" -> value(a[0] as Int)?.toString()
                    "getInt" -> (value(a[0] as Int) as? Number)?.toInt() ?: 0
                    "getLong" -> (value(a[0] as Int) as? Number)?.toLong() ?: 0L
                    "getShort" -> (value(a[0] as Int) as? Number)?.toShort() ?: 0.toShort()
                    "getType" -> when (value(a[0] as Int)) {
                        null -> Cursor.FIELD_TYPE_NULL
                        is Int, is Long -> Cursor.FIELD_TYPE_INTEGER
                        is Number -> Cursor.FIELD_TYPE_FLOAT
                        else -> Cursor.FIELD_TYPE_STRING
                    }
                    else -> null
                }
            }
        }
    }
}
