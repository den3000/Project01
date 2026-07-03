package ru.den.writes.code.agenticHub.platform.database.di

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.platform.database.AppDatabase
import ru.den.writes.code.agenticHub.platform.database.MessageDao
import ru.den.writes.code.agenticHub.platform.database.MessageEntity
import ru.den.writes.code.agenticHub.testing.TestDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DatabaseTestModuleTest {

    @Test
    fun `when databaseTestModule binds a TestDb - then the graph resolves that real db and dao`() = runTest {
        TestDb().use { harness ->
            // given
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin

            // when
            val dao = koin.get<MessageDao>()
            dao.insert(MessageEntity(sessionId = "s", role = "USER", text = "hi"))

            // then — real SQL round-trip, and the graph's AppDatabase IS the test's db
            assertEquals(listOf("hi"), dao.all("s").map { it.text })
            assertSame(harness.db, koin.get<AppDatabase>())
        }
    }
}
