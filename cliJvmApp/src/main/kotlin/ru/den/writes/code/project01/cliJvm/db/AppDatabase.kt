package ru.den.writes.code.project01.cliJvm.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Single-table Room database holding the chat message history.
 *
 * Schema history:
 * - v1 — original shape: `id`, `session_id`, `role`, `text`.
 * - v2 — adds `model_id` + token-count columns (`prompt_tokens`,
 *   `output_tokens`, `thoughts_tokens`, `total_tokens`) to support the
 *   Day-8 cost-tracking work. All new columns nullable, populated only
 *   for ASSISTANT rows. Migrated by hand via [MIGRATION_1_2] —
 *   straightforward `ALTER TABLE ... ADD COLUMN` per new field, all
 *   nullable so no defaults / backfills required.
 *
 * Plain Kotlin JVM (not KMP), so no `@ConstructedBy` / `expect object`
 * ceremony: Room's reified `databaseBuilder<T>(name = ...)` finds the
 * KSP-generated `AppDatabase_Impl` via reflection at runtime.
 */
@Database(
    entities = [MessageEntity::class],
    version = 2,
    // Disable schema-JSON export — we run hand-written migrations rather
    // than the auto-migration feature, so the schemas/ folder is not
    // needed and Room's "missing schema-location" warning is silenced.
    exportSchema = false,
)
internal abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
}

/**
 * v1 → v2 schema migration: adds the five new ASSISTANT-only columns
 * to the `messages` table. All nullable so existing rows are valid
 * after the upgrade — they just have NULLs in the new fields, which is
 * exactly what HistoryStore expects for "no token data was recorded".
 */
internal val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE messages ADD COLUMN model_id TEXT")
        connection.execSQL("ALTER TABLE messages ADD COLUMN prompt_tokens INTEGER")
        connection.execSQL("ALTER TABLE messages ADD COLUMN output_tokens INTEGER")
        connection.execSQL("ALTER TABLE messages ADD COLUMN thoughts_tokens INTEGER")
        connection.execSQL("ALTER TABLE messages ADD COLUMN total_tokens INTEGER")
    }
}
