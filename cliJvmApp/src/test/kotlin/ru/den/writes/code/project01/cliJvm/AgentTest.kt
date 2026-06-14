package ru.den.writes.code.project01.cliJvm

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import java.io.BufferedReader
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    // --- Day-9 compression -------------------------------------------

    @Test
    fun `compression folds old turns into a summary pair and shrinks the request`() = runTest {
        TestDb().use { harness ->
            val fake = FakeLlmApi().apply {
                queueText("r1")
                queueText("r2")
                queueText("ROLLING SUMMARY")  // summarization call during send 3
                queueText("r3")
            }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "comp")
            val chat = newChat(prompt = "p1", session = "comp")
            val compressor = HistoryCompressor(keepLast = 2, summarizeEvery = 2)

            Agent(
                cliArgs = chat,
                llmApi = fake,
                historyStore = store,
                promptSource = stdinSource("p2\np3\n/exit\n"),
                compressor = compressor,
            ).run()

            // 3 real turns + 1 summarization call.
            assertEquals(4, fake.calls.size)

            // The summarization call (index 2): one USER message carrying the
            // folded turns (p1 / r1).
            assertEquals(1, fake.calls[2].messages.size)
            assertEquals(Role.USER, fake.calls[2].messages[0].role)
            assertTrue(fake.calls[2].messages[0].text.contains("p1"))

            // The third real turn (index 3) leads with the synthetic summary
            // pair, then the recent tail, then the current user turn.
            val sent = fake.calls[3].messages
            assertEquals(
                Message(Role.USER, HistoryCompressor.SUMMARY_FRAME_PREFIX + "ROLLING SUMMARY"),
                sent[0],
            )
            assertEquals(Message(Role.ASSISTANT, HistoryCompressor.ACK_TEXT), sent[1])
            assertEquals(Message(Role.USER, "p3"), sent.last())
            // The folded prefix is gone; the request is shorter than full history.
            assertTrue(sent.none { it.text == "p1" || it.text == "r1" })
            // Strict role alternation on the wire list (Gemini contract).
            assertEquals(Role.USER, sent.first().role)
            sent.zipWithNext().forEach { (a, b) -> assertTrue(a.role != b.role) }
        }
    }

    @Test
    fun `failed summarization degrades to full tail and the real turn still goes out`() = runTest {
        TestDb().use { harness ->
            val fake = FakeLlmApi().apply {
                queueText("r1")
                queueText("r2")
                queue(LlmResult(text = null, error = "summarizer boom"))  // compaction fails
                queueText("r3")
            }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "degrade")
            val chat = newChat(prompt = "p1", session = "degrade")
            val compressor = HistoryCompressor(keepLast = 2, summarizeEvery = 2)

            Agent(
                cliArgs = chat,
                llmApi = fake,
                historyStore = store,
                promptSource = stdinSource("p2\np3\n/exit\n"),
                compressor = compressor,
            ).run()

            assertEquals(4, fake.calls.size)
            // The third real turn carried the FULL history (no summary frame).
            assertEquals(
                listOf(
                    Message(Role.USER, "p1"),
                    Message(Role.ASSISTANT, "r1"),
                    Message(Role.USER, "p2"),
                    Message(Role.ASSISTANT, "r2"),
                    Message(Role.USER, "p3"),
                ),
                fake.calls[3].messages,
            )
            // State never advanced → nothing persisted.
            assertNull(store.loadSummary())
        }
    }

    @Test
    fun `resume uses the persisted summary without re-summarizing covered messages`() = runTest {
        TestDb().use { harness ->
            val dao = harness.db.messageDao()
            // Seed prior history + a persisted summary covering the first pair.
            val seed = HistoryStore(dao, sessionId = "resume")
            seed.append(Message(Role.USER, "p1"))
            seed.append(Message(Role.ASSISTANT, "r1"))
            seed.append(Message(Role.USER, "p2"))
            seed.append(Message(Role.ASSISTANT, "r2"))
            seed.saveSummary(
                summaryText = "PRIOR SUMMARY",
                coveredCount = 2,
                modelId = "gemini-2.5-flash-lite",
                usage = Usage(promptTokens = 100, outputTokens = 10, totalTokens = 110),
            )

            // Fresh run: summarizeEvery high enough that no compaction fires.
            val fake = FakeLlmApi().apply { queueText("r3") }
            val store = HistoryStore(dao, sessionId = "resume")
            val chat = newChat(prompt = "p3", session = "resume")
            val compressor = HistoryCompressor(keepLast = 2, summarizeEvery = 100)

            Agent(
                cliArgs = chat,
                llmApi = fake,
                historyStore = store,
                promptSource = stdinSource("/exit\n"),
                compressor = compressor,
            ).run()

            // Only the single real turn — no summarization call.
            assertEquals(1, fake.calls.size)
            val sent = fake.calls[0].messages
            assertEquals(
                Message(Role.USER, HistoryCompressor.SUMMARY_FRAME_PREFIX + "PRIOR SUMMARY"),
                sent[0],
            )
            assertEquals(Message(Role.ASSISTANT, HistoryCompressor.ACK_TEXT), sent[1])
            // Covered prefix (p1 / r1) must NOT appear in the request.
            assertTrue(sent.none { it.text == "p1" || it.text == "r1" })
            assertEquals(Message(Role.USER, "p2"), sent[2])
            assertEquals(Message(Role.ASSISTANT, "r2"), sent[3])
            assertEquals(Message(Role.USER, "p3"), sent.last())
        }
    }

    @Test
    fun `compression runs in feed mode across chunks`() = runTest {
        TestDb().use { harness ->
            val fake = FakeLlmApi().apply {
                queueText("r-open")    // opening prompt
                queueText("r-c1")      // chunk 1
                queueText("SUMMARY")   // compaction before the chunk-2 turn
                queueText("r-c2")      // chunk 2
            }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "feedcomp")
            val chat = newChat(prompt = "open", session = "feedcomp")
            val compressor = HistoryCompressor(keepLast = 2, summarizeEvery = 2)
            val feed = ChunkedFilePromptSource(
                reader = StringReader("AAABBB"),  // 2 chunks × 3 chars
                chunkChars = 3,
                instruction = "",
            )

            Agent(
                cliArgs = chat,
                llmApi = fake,
                historyStore = store,
                promptSource = feed,
                compressor = compressor,
            ).run()

            // open + chunk1 + (compaction) + chunk2 = 4 calls.
            assertEquals(4, fake.calls.size)
            // The chunk-2 turn (index 3) carries the summary pair.
            assertEquals(
                Message(Role.USER, HistoryCompressor.SUMMARY_FRAME_PREFIX + "SUMMARY"),
                fake.calls[3].messages[0],
            )
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
        compress = false,
        keepLast = 6,
        summarizeEvery = 10,
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
