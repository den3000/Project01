package ru.den.writes.code.project01.cliJvm

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.db.AppDatabase
import ru.den.writes.code.project01.cliJvm.db.MIGRATION_1_2
import ru.den.writes.code.project01.cliJvm.db.MIGRATION_2_3
import ru.den.writes.code.project01.cliJvm.db.MIGRATION_3_4
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Guards the v3 → v4 migration ([MIGRATION_3_4]). Builds a v3 database by hand
 * (the v3 schema + `PRAGMA user_version = 3`), then opens it through
 * Room with all migrations registered. If the migration's DDL drifts from what
 * Room derives from the entities, Room's schema validation throws on open and
 * this test fails — the byte-match safety net the plan calls out (we run
 * hand-written migrations with `exportSchema = false`).
 */
class MigrationTest {

    @Test
    fun `v3 database upgrades to v4, backfilling branch_id to main and keeping rows`() = runTest {
        val dbFile = File.createTempFile("migration-v3-v4", ".db").apply { delete() }
        try {
            buildV3Database(dbFile)

            val db = Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
                .setDriver(BundledSQLiteDriver())
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
            try {
                val dao = db.messageDao()
                // The v3 message row survived, with branch_id backfilled to 'main'.
                val messages = dao.all("s1")
                assertEquals(1, messages.size)
                assertEquals("hello from v3", messages[0].text)
                assertEquals("main", messages[0].branchId)
                // The v3 summary survived under branch 'main' (PK widened in place).
                val summary = dao.getSummary("s1")
                assertEquals("old summary", summary?.summaryText)
                assertEquals("main", summary?.branchId)
                // The new facts table exists (query succeeds) and is empty.
                assertNull(dao.getFacts("s1", "main"))
            } finally {
                db.close()
            }
        } finally {
            listOf("", "-shm", "-wal", "-journal").forEach { File(dbFile.absolutePath + it).delete() }
        }
    }

    /** Lay down the exact v3 schema + a couple of rows + user_version=3. */
    private fun buildV3Database(dbFile: File) {
        val conn = BundledSQLiteDriver().open(dbFile.absolutePath)
        try {
            conn.execSQL(
                "CREATE TABLE IF NOT EXISTS `messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`session_id` TEXT NOT NULL, `role` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                    "`model_id` TEXT, `prompt_tokens` INTEGER, `output_tokens` INTEGER, " +
                    "`thoughts_tokens` INTEGER, `total_tokens` INTEGER)"
            )
            conn.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_session_id` ON `messages` (`session_id`)")
            conn.execSQL(
                "CREATE TABLE IF NOT EXISTS `summaries` (`session_id` TEXT NOT NULL, " +
                    "`summary_text` TEXT NOT NULL, `covered_count` INTEGER NOT NULL, `model_id` TEXT, " +
                    "`prompt_tokens` INTEGER, `output_tokens` INTEGER, `thoughts_tokens` INTEGER, " +
                    "`total_tokens` INTEGER, PRIMARY KEY(`session_id`))"
            )
            conn.execSQL("INSERT INTO `messages` (`session_id`, `role`, `text`) VALUES ('s1', 'USER', 'hello from v3')")
            conn.execSQL(
                "INSERT INTO `summaries` (`session_id`, `summary_text`, `covered_count`) " +
                    "VALUES ('s1', 'old summary', 2)"
            )
            conn.execSQL("PRAGMA user_version = 3")
        } finally {
            conn.close()
        }
    }
}
