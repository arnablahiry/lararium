package com.familydocs.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

class Converters {
    @TypeConverter fun fromMode(m: DisplayMode): String = m.name
    @TypeConverter fun toMode(s: String): DisplayMode = runCatching { DisplayMode.valueOf(s) }.getOrDefault(DisplayMode.ICON)
}

@Database(entities = [AccountEntity::class, MemberEntity::class, DocumentEntity::class, PasswordEntity::class, EventEntity::class, GroupEntity::class], version = 11, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): VaultDao

    companion object {
        /** Whole database encrypted with SQLCipher; the passphrase is a random secret wrapped by the Keystore. */
        fun build(context: Context, passphrase: ByteArray): AppDatabase {
            System.loadLibrary("sqlcipher")
            UpdateGuard.beforeOpen(context) // copy of the vault before any schema change
            return Room.databaseBuilder(context, AppDatabase::class.java, "vault.db")
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(*ALL_MIGRATIONS)
                // deliberately NO fallbackToDestructiveMigration(): updates must never wipe the vault
                .build()
        }
    }
}
