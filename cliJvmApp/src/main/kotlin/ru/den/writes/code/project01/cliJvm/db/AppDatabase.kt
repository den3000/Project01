package ru.den.writes.code.project01.cliJvm.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Single-table Room database holding the chat message history.
 *
 * Schema version pinned at 1. If/when we change the schema, add a
 * proper [androidx.room.migration.Migration] (or temporarily fall back
 * to destructive migration) — there's nothing in here today.
 *
 * Plain Kotlin JVM (not KMP), so no `@ConstructedBy` / `expect object`
 * ceremony: Room's reified `databaseBuilder<T>(name = ...)` finds the
 * KSP-generated `AppDatabase_Impl` via reflection at runtime.
 */
@Database(
    entities = [MessageEntity::class],
    version = 1,
    // Disable schema-JSON export until we actually need migrations. Room
    // warns when this is true (the default) and no schemaLocation is set;
    // false silences it for this learning-project setup.
    exportSchema = false,
)
internal abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
}
