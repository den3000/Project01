package ru.den.writes.code.agenticHub.features.lifecycle.start

import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem
import ru.den.writes.code.agenticHub.platform.filesystem.di.fileSystemModule
import ru.den.writes.code.agenticHub.platform.database.AppDatabase
import ru.den.writes.code.agenticHub.platform.database.di.databaseTestModule
import org.koin.dsl.koinApplication
import kotlinx.coroutines.test.runTest
import ru.den.writes.code.agenticHub.platform.database.TestDb
import ru.den.writes.code.agenticHub.features.lifecycle.start.StartExecutor
import ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand
import ru.den.writes.code.agenticHub.features.llm.ModelProvider
import ru.den.writes.code.agenticHub.features.llm.gemini.GeminiModel
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The [StartExecutor.execute] contract: admin commands run here and yield null; a
 * session command is returned unrun for `main` to launch. This pins the split
 * between "the executor finishes it" and "the executor hands it back".
 */
class StartExecutorTest {

    @Test
    fun `when a session command - then it is returned unrun`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            // given
            val oneShot = StartCommand.RunOneShot(
                prompt = "hi",
                maxTokens = null,
                stopSequences = null,
                endSequence = null,
                temperature = null,
                modelProvider = ModelProvider.Gemini(model = GeminiModel.Default, apiKey = "test-key"),
            )

            // when
            val result = StartExecutor(koin.get<AppDatabase>(), koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>()).execute(oneShot)

            // then — same object back, nothing launched
            assertSame(oneShot, result)
        }
    }

    @Test
    fun `when an admin command - then it runs and returns null`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            // when — list runs against the (empty) test db
            val result = StartExecutor(koin.get<AppDatabase>(), koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>()).execute(StartCommand.ListSessions)

            // then
            assertNull(result)
        }
    }
}
