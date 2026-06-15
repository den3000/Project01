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
 * - v3 — adds the `summaries` table (one row per session) for the Day-9
 *   history-compression feature: the rolling summary + `covered_count`
 *   watermark + cumulative summarization-token columns. Created by
 *   [MIGRATION_2_3]; the `messages` table is untouched, so existing
 *   history is unaffected.
 *
 * Plain Kotlin JVM (not KMP), so no `@ConstructedBy` / `expect object`
 * ceremony: Room's reified `databaseBuilder<T>(name = ...)` finds the
 * KSP-generated `AppDatabase_Impl` via reflection at runtime.
 */
@Database(
    entities = [MessageEntity::class, SummaryEntity::class],
    version = 3,
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

/**
 * v2 → v3 schema migration: creates the `summaries` table for the Day-9
 * history-compression feature. The `messages` table is left untouched.
 *
 * The DDL deliberately mirrors the schema Room generates from
 * [SummaryEntity] — column types, `NOT NULL` on the non-null fields, and
 * `session_id` as the primary key. With `exportSchema = false` there's no
 * schema JSON to diff against, so if this drifts from the entity Room's
 * runtime identity check throws on the first open of a migrated DB.
 */
internal val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `summaries` (" +
                "`session_id` TEXT NOT NULL, " +
                "`summary_text` TEXT NOT NULL, " +
                "`covered_count` INTEGER NOT NULL, " +
                "`model_id` TEXT, " +
                "`prompt_tokens` INTEGER, " +
                "`output_tokens` INTEGER, " +
                "`thoughts_tokens` INTEGER, " +
                "`total_tokens` INTEGER, " +
                "PRIMARY KEY(`session_id`))"
        )
    }
}
