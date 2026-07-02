package ru.den.writes.code.project01.cliJvm

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.command.StartCommand
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
            val result = StartExecutor(harness.db).execute(oneShot)

            // then — same object back, nothing launched
            assertSame(oneShot, result)
        }
    }

    @Test
    fun `when an admin command - then it runs and returns null`() = runTest {
        TestDb().use { harness ->
            // when — list runs against the (empty) test db
            val result = StartExecutor(harness.db).execute(StartCommand.ListSessions)

            // then
            assertNull(result)
        }
    }
}
