package ru.den.writes.code.project01.cliJvm

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import java.io.BufferedReader
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentTest {

    // --- OneShot mode -----------------------------------------------

    @Test
    fun `OneShot does not load history and does not persist anything`() = runTest {
        TestDb().use { harness ->
            val dao = harness.db.messageDao()
            // Pre-existing rows that OneShot must NOT load nor see.
            val seeded = HistoryStore(dao, sessionId = "ignored")
            seeded.append(Message(Role.USER, "old turn"))
            val priorCount = dao.count()

            val fake = FakeLlmApi().apply { queue("ok") }
            val oneShot = CliArgs.OneShot(
                prompt = "fire and forget",
                maxTokens = null,
                stopSequences = null,
                endSequence = null,
                temperature = null,
                modelProvider = dummyGeminiProvider(),
            )

            Agent(oneShot, fake, historyStore = null, stdin = emptyStdin()).run()

            assertEquals(1, fake.calls.size)
            assertEquals(
                listOf(Message(Role.USER, "fire and forget")),
                fake.calls[0].messages,
            )
            assertEquals(priorCount, dao.count())
        }
    }

    @Test
    fun `OneShot forwards generation params verbatim`() = runTest {
        val fake = FakeLlmApi().apply { queue("ok") }
        val oneShot = CliArgs.OneShot(
            prompt = "x",
            maxTokens = 42,
            stopSequences = listOf("STOP"),
            endSequence = "[END]",
            temperature = 0.5,
            modelProvider = dummyGeminiProvider(GeminiModel.Known.Gemini25Flash),
        )

        Agent(oneShot, fake, historyStore = null, stdin = emptyStdin()).run()

        assertEquals(
            GenerationParams(
                maxTokens = 42,
                stopSequences = listOf("STOP"),
                endSequence = "[END]",
                temperature = 0.5,
            ),
            fake.calls.single().params,
        )
    }

    // --- Chat mode --------------------------------------------------

    @Test
    fun `Chat with empty history sends opening prompt alone and persists turn`() = runTest {
        TestDb().use { harness ->
            val fake = FakeLlmApi().apply { queue("model reply") }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            val chat = newChat(prompt = "hi", session = "alpha")

            Agent(chat, fake, store, stdin = stdinScript("/exit\n")).run()

            assertEquals(1, fake.calls.size)
            assertEquals(
                listOf(Message(Role.USER, "hi")),
                fake.calls[0].messages,
            )
            // Fresh reader sees both rows in the DB.
            val reader = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            reader.load()
            assertEquals(
                listOf(
                    Message(Role.USER, "hi"),
                    Message(Role.ASSISTANT, "model reply"),
                ),
                reader.messages,
            )
        }
    }

    @Test
    fun `Chat with restored history sends history plus new user turn`() = runTest {
        TestDb().use { harness ->
            val dao = harness.db.messageDao()
            val seeder = HistoryStore(dao, sessionId = "alpha")
            seeder.append(Message(Role.USER, "earlier user"))
            seeder.append(Message(Role.ASSISTANT, "earlier assistant"))

            val fake = FakeLlmApi().apply { queue("ok") }
            val store = HistoryStore(dao, sessionId = "alpha")
            val chat = newChat(prompt = "next", session = "alpha")

            Agent(chat, fake, store, stdin = stdinScript("/exit\n")).run()

            assertEquals(
                listOf(
                    Message(Role.USER, "earlier user"),
                    Message(Role.ASSISTANT, "earlier assistant"),
                    Message(Role.USER, "next"),
                ),
                fake.calls.single().messages,
            )
        }
    }

    @Test
    fun `Failed opening turn leaves the DB untouched`() = runTest {
        TestDb().use { harness ->
            val fake = FakeLlmApi()  // empty queue → returns null
            val store = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            val chat = newChat(prompt = "hi", session = "alpha")

            Agent(chat, fake, store, stdin = stdinScript("/exit\n")).run()

            assertEquals(0, harness.db.messageDao().count())
        }
    }

    @Test
    fun `reuse resends last model reply as the next user turn`() = runTest {
        TestDb().use { harness ->
            val fake = FakeLlmApi().apply { queue("first reply", "second reply") }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            val chat = newChat(prompt = "start", session = "alpha")

            Agent(chat, fake, store, stdin = stdinScript("/reuse\n/exit\n")).run()

            assertEquals(2, fake.calls.size)
            // Second call must end with "first reply" as a USER turn —
            // that's what /reuse does: copy last model output to next user.
            assertEquals(
                Message(Role.USER, "first reply"),
                fake.calls[1].messages.last(),
            )
        }
    }

    @Test
    fun `reuse is a no-op when there is no prior reply`() = runTest {
        TestDb().use { harness ->
            val fake = FakeLlmApi()  // empty queue → opening fails, no reply to reuse
            val store = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            val chat = newChat(prompt = "start", session = "alpha")

            Agent(chat, fake, store, stdin = stdinScript("/reuse\n/exit\n")).run()

            // Only the failed opening attempt. /reuse silently skipped
            // because `response` is still null.
            assertEquals(1, fake.calls.size)
        }
    }

    @Test
    fun `EOF on stdin exits the REPL cleanly`() = runTest {
        TestDb().use { harness ->
            val fake = FakeLlmApi().apply { queue("ok") }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            val chat = newChat(prompt = "hi", session = "alpha")

            // No /exit — just close the stream immediately after opening.
            Agent(chat, fake, store, stdin = stdinScript("")).run()

            // Opening turn went out; REPL didn't try to read anything else.
            assertEquals(1, fake.calls.size)
        }
    }

    @Test
    fun `Chat without restored history does not print resumed banner`() = runTest {
        // Sanity: empty history → no "resumed" line on stderr. We don't
        // assert on stderr here (would require capturing), but exercise
        // the path to make sure it doesn't crash on an empty store.
        TestDb().use { harness ->
            val fake = FakeLlmApi().apply { queue("ok") }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "fresh")
            val chat = newChat(prompt = "hi", session = "fresh")

            Agent(chat, fake, store, stdin = stdinScript("/exit\n")).run()

            assertTrue(fake.calls.isNotEmpty())
        }
    }

    // --- helpers ----------------------------------------------------

    private fun newChat(prompt: String, session: String?): CliArgs.Chat = CliArgs.Chat(
        prompt = prompt,
        maxTokens = null,
        stopSequences = null,
        endSequence = null,
        temperature = null,
        modelProvider = dummyGeminiProvider(),
        session = session,
    )

    /**
     * Agent doesn't dispatch on the provider (the concrete `LlmApi` is
     * already stubbed via `FakeLlmApi`), but `CliArgs.PromptCommand`
     * insists on a non-null [ModelProvider], so tests pass this throwaway.
     */
    private fun dummyGeminiProvider(
        model: GeminiModel = GeminiModel.Default,
    ): ModelProvider.Gemini = ModelProvider.Gemini(model = model, apiKey = "test-key")

    /** Pre-loaded stdin that hands the REPL the given script line by line. */
    private fun stdinScript(script: String): BufferedReader =
        BufferedReader(StringReader(script))

    /** Empty stdin — useful when the test path never asks for input (OneShot). */
    private fun emptyStdin(): BufferedReader = stdinScript("")
}
