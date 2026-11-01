package com.familydocs.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * How updates keep your data
 * --------------------------
 * Every time the database layout changes:
 *   1. bump `version` in [AppDatabase],
 *   2. add a Migration(old, new) below that ALTERs/creates tables (never drops user data),
 *   3. commit the new JSON in app/schemas/ (Room writes it on build) so the change can be checked.
 * The database is never opened with fallbackToDestructiveMigration, so a missing migration
 * makes the app refuse to start instead of silently wiping the vault.
 *
 * Example for a future version 2:
 *   val MIGRATION_1_2 = object : Migration(1, 2) {
 *       override fun migrate(db: SupportSQLiteDatabase) {
 *           db.execSQL("ALTER TABLE documents ADD COLUMN notes TEXT NOT NULL DEFAULT ''")
 *       }
 *   }
 */
/** v2: members get an illustrated default character. Existing rows keep everything and get no character (initials until one is picked). */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE members ADD COLUMN avatar TEXT")
    }
}

/** v3: events (trips, birthdays, appointments) and event-only documents. Nothing existing changes. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `events` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `art` TEXT NOT NULL, `photoFile` TEXT, " +
                "`startDay` TEXT NOT NULL, `endDay` TEXT, `people` TEXT NOT NULL, `visibleTo` TEXT NOT NULL, `pins` TEXT NOT NULL, " +
                "`remindDays` INTEGER NOT NULL, `notes` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL("ALTER TABLE documents ADD COLUMN eventId TEXT")
    }
}

/** v4: per-document "show flag on card" choice (NULL = the type's default). Nothing existing changes. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE documents ADD COLUMN showFlag INTEGER")
    }
}

/** v5: several files per document (front, back, more pages): the extra ones as a JSON list. Nothing existing changes. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE documents ADD COLUMN moreFiles TEXT")
    }
}

/** v6: the order of a person's cards (hold and drag). NULL = in the order added. Nothing existing changes. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE documents ADD COLUMN sortOrder INTEGER")
    }
}

/**
 * v7: groups. Everything already on the phone becomes the first group, "Family" (with a fresh code); you stay in every
 * group. Nothing is deleted.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `vault_groups` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `code` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("ALTER TABLE members ADD COLUMN groupId TEXT")
        db.execSQL("ALTER TABLE events ADD COLUMN groupId TEXT")
        val hasPeople = db.query("SELECT COUNT(*) FROM members").use { it.moveToFirst() && it.getInt(0) > 0 }
        if (hasPeople) {
            val id = java.util.UUID.randomUUID().toString()
            db.execSQL("INSERT INTO vault_groups (id, name, code, createdAt) VALUES (?, ?, ?, ?)",
                arrayOf<Any>(id, "Family", com.familydocs.app.domain.GroupCodes.new(), System.currentTimeMillis()))
            db.execSQL("UPDATE members SET groupId = ? WHERE isSelf = 0", arrayOf<Any>(id))
            db.execSQL("UPDATE events SET groupId = ?", arrayOf<Any>(id))
        }
    }
}

/** v8: sharing a group between accounts, the per-group backup choice and group pictures. Columns only; nothing changes for existing data. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE vault_groups ADD COLUMN backupAll INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE vault_groups ADD COLUMN shareFolder TEXT")
        db.execSQL("ALTER TABLE vault_groups ADD COLUMN shareIndex TEXT")
        db.execSQL("ALTER TABLE vault_groups ADD COLUMN peers TEXT")
        db.execSQL("ALTER TABLE vault_groups ADD COLUMN pic TEXT")
        db.execSQL("ALTER TABLE vault_groups ADD COLUMN photoFile TEXT")
        db.execSQL("ALTER TABLE members ADD COLUMN origin TEXT")
        db.execSQL("ALTER TABLE documents ADD COLUMN origin TEXT")
        db.execSQL("ALTER TABLE passwords ADD COLUMN origin TEXT")
    }
}

/** Version 9: group admins (who made the group, who else is an admin). */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE vault_groups ADD COLUMN creator TEXT")
        db.execSQL("ALTER TABLE vault_groups ADD COLUMN admins TEXT")
        db.execSQL("ALTER TABLE vault_groups ADD COLUMN adminsAt INTEGER NOT NULL DEFAULT 0")
    }
}

/** Version 10: events shared with others can be changed by all of them (the newest change wins). */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE events ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE events ADD COLUMN editedBy TEXT")
    }
}

/** Version 11: old documents put away when a renewed one is added. */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE documents ADD COLUMN archivedAt INTEGER")
    }
}

val ALL_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_5_6,
    MIGRATION_6_7,
    MIGRATION_7_8,
    MIGRATION_8_9,
    MIGRATION_9_10,
    MIGRATION_10_11,
)
