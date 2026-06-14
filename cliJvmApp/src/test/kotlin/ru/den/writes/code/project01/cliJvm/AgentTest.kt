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

            val fake = FakeLlmApi().apply { queueText("ok") }
            val oneShot = CliArgs.OneShot(
                prompt = "fire and forget",
                maxTokens = null,
                stopSequences = null,
                endSequence = null,
                temperature = null,
                modelProvider = dummyGeminiProvider(),
            )

            Agent(oneShot, fake, historyStore = null, promptSource = stdinSource("")).run()

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
        val fake = FakeLlmApi().apply { queueText("ok") }
        val oneShot = CliArgs.OneShot(
            prompt = "x",
            maxTokens = 42,
            stopSequences = listOf("STOP"),
            endSequence = "[END]",
            temperature = 0.5,
            modelProvider = dummyGeminiProvider(GeminiModel.Known.Gemini25Flash),
        )

        Agent(oneShot, fake, historyStore = null, promptSource = stdinSource("")).run()

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
            val fake = FakeLlmApi().apply { queueText("model reply") }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            val chat = newChat(prompt = "hi", session = "alpha")

            Agent(chat, fake, store, promptSource = stdinSource("/exit\n")).run()

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

            val fake = FakeLlmApi().apply { queueText("ok") }
            val store = HistoryStore(dao, sessionId = "alpha")
            val chat = newChat(prompt = "next", session = "alpha")

            Agent(chat, fake, store, promptSource = stdinSource("/exit\n")).run()

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
            val fake = FakeLlmApi()  // empty queue → returns error
            val store = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            val chat = newChat(prompt = "hi", session = "alpha")

            Agent(chat, fake, store, promptSource = stdinSource("/exit\n")).run()

            assertEquals(0, harness.db.messageDao().count())
        }
    }

    @Test
    fun `reuse resends last model reply as the next user turn`() = runTest {
        TestDb().use { harness ->
            val fake = FakeLlmApi().apply {
                queueText("first reply")
                queueText("second reply")
            }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            val chat = newChat(prompt = "start", session = "alpha")

            Agent(chat, fake, store, promptSource = stdinSource("/reuse\n/exit\n")).run()

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

            Agent(chat, fake, store, promptSource = stdinSource("/reuse\n/exit\n")).run()

            // Only the failed opening attempt. /reuse silently skipped
            // because StdinPromptSource has no cached reply yet.
            assertEquals(1, fake.calls.size)
        }
    }

    @Test
    fun `EOF on stdin exits the REPL cleanly`() = runTest {
        TestDb().use { harness ->
            val fake = FakeLlmApi().apply { queueText("ok") }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            val chat = newChat(prompt = "hi", session = "alpha")

            // No /exit — just close the stream immediately after opening.
            Agent(chat, fake, store, promptSource = stdinSource("")).run()

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
            val fake = FakeLlmApi().apply { queueText("ok") }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "fresh")
            val chat = newChat(prompt = "hi", session = "fresh")

            Agent(chat, fake, store, promptSource = stdinSource("/exit\n")).run()

            assertTrue(fake.calls.isNotEmpty())
        }
    }

    // --- Token accounting --------------------------------------------

    @Test
    fun `successful turn folds usage into HistoryStore stats`() = runTest {
        TestDb().use { harness ->
            val fake = FakeLlmApi().apply {
                queue(
                    LlmResult(
                        text = "ok",
                        usage = Usage(
                            promptTokens = 12,
                            outputTokens = 7,
                            thoughtsTokens = 3,
                            totalTokens = 22,
                        ),
                    )
                )
            }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "tally")
            val chat = newChat(prompt = "hi", session = "tally")

            Agent(chat, fake, store, promptSource = stdinSource("/exit\n")).run()

            assertEquals(1, store.stats.turns)
            assertEquals(12, store.stats.totalPromptTokens)
            assertEquals(7, store.stats.totalOutputTokens)
            assertEquals(3, store.stats.totalThoughtsTokens)
        }
    }

    @Test
    fun `resume reseeds stats from existing DB rows`() = runTest {
        TestDb().use { harness ->
            // Phase 1: persist a turn with token data.
            val w = HistoryStore(harness.db.messageDao(), sessionId = "rs")
            w.append(Message(Role.USER, "prev user"))
            w.append(
                Message(Role.ASSISTANT, "prev reply"),
                usage = Usage(promptTokens = 100, outputTokens = 50, totalTokens = 150),
                modelId = "gemini-2.5-flash-lite",
            )

            // Phase 2: open a fresh store, run a turn, check that stats
            // reflect prior + new.
            val fake = FakeLlmApi().apply { queueText("next reply", promptTokens = 200, outputTokens = 20) }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "rs")
            val chat = newChat(prompt = "next", session = "rs")
            Agent(chat, fake, store, promptSource = stdinSource("/exit\n")).run()

            assertEquals(2, store.stats.turns)
            assertEquals(300, store.stats.totalPromptTokens)        // 100 + 200
            assertEquals(70, store.stats.totalOutputTokens)         // 50 + 20
        }
    }

    @Test
    fun `error from llmApi aborts feed loop after current chunk`() = runTest {
        TestDb().use { harness ->
            val fake = FakeLlmApi().apply {
                queueText("opening ok")        // -prompt opener
                queueText("chunk 1 ok")        // first chunk from feed file
                queue(LlmResult(text = null, error = "synthetic 400"))  // second chunk fails
            }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "feed")
            val chat = newChat(prompt = "go", session = "feed")

            // Feed source: three chunks scripted. Loop should stop after
            // the failed turn even though chunk 3 is still in the source.
            val source = ChunkedFilePromptSource(
                reader = StringReader("AAA" + "BBB" + "CCC"),
                chunkChars = 3,
                instruction = "",
            )
            Agent(chat, fake, store, promptSource = source).run()

            // Calls: opener + chunk1 + chunk2 (failed). chunk3 never sent.
            assertEquals(3, fake.calls.size)
        }
    }

    @Test
    fun `chunked feed source yields successive chunks`() {
        val source = ChunkedFilePromptSource(
            reader = StringReader("hello world!!"),
            chunkChars = 5,
            instruction = "",
        )
        assertEquals("hello", source.nextPrompt())
        assertEquals(" worl", source.nextPrompt())
        assertEquals("d!!", source.nextPrompt())
        assertEquals(null, source.nextPrompt())
    }

    @Test
    fun `chunked feed source wraps each chunk in the instruction prefix when set`() {
        val source = ChunkedFilePromptSource(
            reader = StringReader("12345"),
            chunkChars = 5,
            instruction = "Comment:",
        )
        assertEquals("Comment:\n\n12345", source.nextPrompt())
        assertEquals(null, source.nextPrompt())
    }

    // --- context-fill formatting ------------------------------------

    @Test
    fun `formatContextFill renders prompt over window with one decimal pct`() {
        assertEquals(
            "context: 120000 / 1000000 (12.0%)",
            formatContextFill(promptTokens = 120_000, windowTokens = 1_000_000),
        )
    }

    @Test
    fun `formatContextFill handles zero and full edge cases`() {
        assertEquals(
            "context: 0 / 1000000 (0.0%)",
            formatContextFill(promptTokens = 0, windowTokens = 1_000_000),
        )
        assertEquals(
            "context: 1000000 / 1000000 (100.0%)",
            formatContextFill(promptTokens = 1_000_000, windowTokens = 1_000_000),
        )
    }

    @Test
    fun `feed source natural exhaustion hands off to replAfterFeed`() = runTest {
        TestDb().use { harness ->
            val fake = FakeLlmApi().apply {
                queueText("opener reply")
                queueText("chunk1 reply")
                queueText("chunk2 reply")
                queueText("stdin reply")
            }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "handoff")
            val chat = newChat(prompt = "open", session = "handoff")

            val feedSource = ChunkedFilePromptSource(
                reader = StringReader("ABCDEF"),  // 2 chunks × 3 chars
                chunkChars = 3,
                instruction = "",
            )
            val stdinAfter = StdinPromptSource(
                BufferedReader(StringReader("after-feed\n/exit\n"))
            )

            Agent(
                cliArgs = chat,
                llmApi = fake,
                historyStore = store,
                promptSource = feedSource,
                replAfterFeed = stdinAfter,
            ).run()

            // 1 opener + 2 chunks + 1 stdin prompt = 4 calls. Then /exit
            // stops the stdin loop, finally prints session-summary.
            assertEquals(4, fake.calls.size)
            assertEquals("after-feed", fake.calls[3].messages.last().text)
        }
    }

    @Test
    fun `feed abort still transitions to replAfterFeed for manual probing`() = runTest {
        TestDb().use { harness ->
            val fake = FakeLlmApi().apply {
                queueText("opener ok")
                queue(LlmResult(text = null, error = "synthetic overflow"))  // chunk1 fails
                queueText("manual probe reply")  // user follow-up after the failure
            }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "abort")
            val chat = newChat(prompt = "open", session = "abort")

            val feedSource = ChunkedFilePromptSource(
                reader = StringReader("AAABBB"),
                chunkChars = 3,
                instruction = "",
            )
            val stdinAfter = StdinPromptSource(
                BufferedReader(StringReader("manual probe\n/exit\n"))
            )

            Agent(
                cliArgs = chat,
                llmApi = fake,
                historyStore = store,
                promptSource = feedSource,
                replAfterFeed = stdinAfter,
            ).run()

            // opener + chunk1 (failed) + manual REPL probe = 3 calls.
            // The transition happens despite feedSource.terminated = true:
            // we let the user keep poking the model after the first error.
            assertEquals(3, fake.calls.size)
            assertEquals("manual probe", fake.calls[2].messages.last().text)
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
        feedFile = null,
        chunkChars = 2500,
        feedInstruction = "",
    )

    /**
     * Agent doesn't dispatch on the provider (the concrete `LlmApi` is
     * already stubbed via `FakeLlmApi`), but `CliArgs.PromptCommand`
     * insists on a non-null [ModelProvider], so tests pass this throwaway.
     */
    private fun dummyGeminiProvider(
        model: GeminiModel = GeminiModel.Default,
    ): ModelProvider.Gemini = ModelProvider.Gemini(model = model, apiKey = "test-key")

    /** Pre-loaded stdin source that hands the REPL the given script line by line. */
    private fun stdinSource(script: String): StdinPromptSource =
        StdinPromptSource(BufferedReader(StringReader(script)))
}
