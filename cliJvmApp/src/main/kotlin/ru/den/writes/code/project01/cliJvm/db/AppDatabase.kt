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
 *   `output_tokens`, `thoughts_tokens`, `total_tokens`) for cost
 *   tracking. All new columns nullable, populated only for ASSISTANT
 *   rows. Migrated by hand via [MIGRATION_1_2] — straightforward `ALTER
 *   TABLE ... ADD COLUMN` per new field, all nullable so no defaults /
 *   backfills required.
 * - v3 — adds the `summaries` table (one row per session) for
 *   history-compression: the rolling summary + `covered_count` watermark
 *   + cumulative summarization-token columns. Created by [MIGRATION_2_3];
 *   the `messages` table is untouched, so existing history is unaffected.
 * - v4 — branching + sticky facts: adds a `branch_id` discriminator to
 *   `messages` (composite `(session_id, branch_id)` index) and to
 *   `summaries` (composite PK), and adds the new `facts` table. Created by
 *   [MIGRATION_3_4], which rebuilds the two existing tables.
 *
 * Plain Kotlin JVM (not KMP), so no `@ConstructedBy` / `expect object`
 * ceremony: Room's reified `databaseBuilder<T>(name = ...)` finds the
 * KSP-generated `AppDatabase_Impl` via reflection at runtime.
 */
@Database(
    entities = [MessageEntity::class, SummaryEntity::class, FactsEntity::class],
    version = 4,
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
 * v2 → v3 schema migration: creates the `summaries` table for
 * history-compression. The `messages` table is left untouched.
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

/**
 * v3 → v4 schema migration (branching + sticky facts):
 *  - adds a NOT-NULL `branch_id` column to `messages` (backfilled to
 *    `'main'`) and swaps its index to the composite `(session_id, branch_id)`;
 *  - adds `branch_id` to `summaries` and widens its primary key to the
 *    composite `(session_id, branch_id)`;
 *  - creates the new `facts` table (one row per (session, branch)).
 *
 * `messages` and `summaries` are **rebuilt** (CREATE new → INSERT … SELECT
 * with a literal `'main'` → DROP → RENAME) rather than ALTER-ed: SQLite can't
 * add a NOT-NULL column without a SQL default, nor change a primary key in
 * place, and we need each table's DDL to byte-match what Room derives from the
 * entities (no SQL default on `branch_id`) — otherwise Room's identity check
 * throws on first open. The CREATE statements are copied verbatim from the
 * generated `AppDatabase_Impl.createAllTables`.
 */
internal val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        // messages: rebuild with branch_id (backfilled 'main') + composite index.
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `messages_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`session_id` TEXT NOT NULL, `role` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                "`model_id` TEXT, `prompt_tokens` INTEGER, `output_tokens` INTEGER, " +
                "`thoughts_tokens` INTEGER, `total_tokens` INTEGER, `branch_id` TEXT NOT NULL)"
        )
        connection.execSQL(
            "INSERT INTO `messages_new` (`id`, `session_id`, `role`, `text`, `model_id`, " +
                "`prompt_tokens`, `output_tokens`, `thoughts_tokens`, `total_tokens`, `branch_id`) " +
                "SELECT `id`, `session_id`, `role`, `text`, `model_id`, `prompt_tokens`, " +
                "`output_tokens`, `thoughts_tokens`, `total_tokens`, 'main' FROM `messages`"
        )
        connection.execSQL("DROP TABLE `messages`")
        connection.execSQL("ALTER TABLE `messages_new` RENAME TO `messages`")
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_messages_session_id_branch_id` " +
                "ON `messages` (`session_id`, `branch_id`)"
        )

        // summaries: rebuild with branch_id (backfilled 'main') + composite PK.
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `summaries_new` (`session_id` TEXT NOT NULL, " +
                "`summary_text` TEXT NOT NULL, `covered_count` INTEGER NOT NULL, `model_id` TEXT, " +
                "`prompt_tokens` INTEGER, `output_tokens` INTEGER, `thoughts_tokens` INTEGER, " +
                "`total_tokens` INTEGER, `branch_id` TEXT NOT NULL, PRIMARY KEY(`session_id`, `branch_id`))"
        )
        connection.execSQL(
            "INSERT INTO `summaries_new` (`session_id`, `summary_text`, `covered_count`, `model_id`, " +
                "`prompt_tokens`, `output_tokens`, `thoughts_tokens`, `total_tokens`, `branch_id`) " +
                "SELECT `session_id`, `summary_text`, `covered_count`, `model_id`, `prompt_tokens`, " +
                "`output_tokens`, `thoughts_tokens`, `total_tokens`, 'main' FROM `summaries`"
        )
        connection.execSQL("DROP TABLE `summaries`")
        connection.execSQL("ALTER TABLE `summaries_new` RENAME TO `summaries`")

        // facts: brand-new table for sticky facts.
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `facts` (`session_id` TEXT NOT NULL, `branch_id` TEXT NOT NULL, " +
                "`facts_json` TEXT NOT NULL, `model_id` TEXT, `prompt_tokens` INTEGER, " +
                "`output_tokens` INTEGER, `thoughts_tokens` INTEGER, `total_tokens` INTEGER, " +
                "PRIMARY KEY(`session_id`, `branch_id`))"
        )
    }
}
