package com.github.kr328.clash.service.data.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE imported ADD COLUMN profileOrder INTEGER NOT NULL DEFAULT 0")
        database.execSQL("UPDATE imported SET profileOrder = createdAt")
        database.execSQL("ALTER TABLE pending ADD COLUMN profileOrder INTEGER NOT NULL DEFAULT 0")
        database.execSQL("UPDATE pending SET profileOrder = createdAt")
    }
}

internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE imported ADD COLUMN ageSecretKey TEXT")
        database.execSQL("ALTER TABLE pending ADD COLUMN ageSecretKey TEXT")
    }
}

/** Desync-only profiles: their generator settings (JSON), null for every existing profile. */
internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE imported ADD COLUMN desyncSettings TEXT")
        database.execSQL("ALTER TABLE pending ADD COLUMN desyncSettings TEXT")
    }
}

val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

val LEGACY_MIGRATION = ::migrationFromLegacy
