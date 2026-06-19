package ru.den.writes.code.project01.cliJvm.agent

import ru.den.writes.code.project01.shared.llm.LlmResult
import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.SessionLoop
import ru.den.writes.code.project01.cliJvm.ChunkedFilePromptSource
import ru.den.writes.code.project01.cliJvm.FakeLlmApi
import ru.den.writes.code.project01.cliJvm.LineFilePromptSource
import ru.den.writes.code.project01.cliJvm.StdinPromptSource
import ru.den.writes.code.project01.cliJvm.TestDb
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import java.io.BufferedReader
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentFeedModeTest {

    //region feed loop error handling

    @Test
    fun `when feed chunk fails - then loop aborts after current chunk and remaining chunks not sent`() = runTest {
        TestDb().use { harness ->
            // given
            val fakeApi = FakeLlmApi().apply {
                queueText("opening ok")  // -prompt opener
                queueText("chunk 1 ok")  // first chunk from feed file
                queue(LlmResult(text = null, error = "synthetic 400")) // second chunk fails
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

            // when
            SessionLoop(chat, fakeApi, store, promptSource = source).run()

            // then
            // Calls: opener + chunk1 + chunk2 (failed). chunk3 never sent.
            assertEquals(3, fakeApi.calls.size)
        }
    }
    //endregion

    //region line feed source driving Agent

    @Test
    fun `when LineFilePromptSource drives Agent - then one turn per line plus opening prompt`() = runTest {
        TestDb().use { harness ->
            // given
            val fakeApi = FakeLlmApi().apply {
                queueText("r-open")
                queueText("r1")
                queueText("r2")
            }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "lines")
            val chat = newChat(prompt = "open", session = "lines")
            val source = LineFilePromptSource(BufferedReader(StringReader("turn one\nturn two\n")))

            // when
            SessionLoop(chat, fakeApi, store, promptSource = source).run()

            // then
            // opening -prompt + 2 lines = 3 turns.
            assertEquals(3, fakeApi.calls.size)
            assertEquals("turn one", fakeApi.calls[1].messages.last().text)
            assertEquals("turn two", fakeApi.calls[2].messages.last().text)
        }
    }
    //endregion

    //region handoff to replAfterFeed

    @Test
    fun `when feed source naturally exhausts - then control hands off to replAfterFeed`() = runTest {
        TestDb().use { harness ->
            // given
            val fakeApi = FakeLlmApi().apply {
                queueText("opener reply")
                queueText("chunk1 reply")
                queueText("chunk2 reply")
                queueText("stdin reply")
            }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "handoff")
            val chat = newChat(prompt = "open", session = "handoff")
            val feedSource = ChunkedFilePromptSource(
                reader = StringReader("ABCDEF"), // 2 chunks × 3 chars
                chunkChars = 3,
                instruction = "",
            )
            val stdinAfter = StdinPromptSource(BufferedReader(StringReader("after-feed\n/exit\n")))

            // when
            SessionLoop(
                cliArgs = chat,
                llmApi = fakeApi,
                historyStore = store,
                promptSource = feedSource,
                replAfterFeed = stdinAfter,
            ).run()

            // then
            // 1 opener + 2 chunks + 1 stdin prompt = 4 calls. Then /exit
            // stops the stdin loop, finally prints session-summary.
            assertEquals(4, fakeApi.calls.size)
            assertEquals("after-feed", fakeApi.calls[3].messages.last().text)
        }
    }

    @Test
    fun `when feed source aborts on error - then still transitions to replAfterFeed for manual probing`() = runTest {
        TestDb().use { harness ->
            // given
            val fakeApi = FakeLlmApi().apply {
                queueText("opener ok")
                queue(LlmResult(text = null, error = "synthetic overflow")) // chunk1 fails
                queueText("manual probe reply") // user follow-up after the failure
            }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "abort")
            val chat = newChat(prompt = "open", session = "abort")
            val feedSource = ChunkedFilePromptSource(
                reader = StringReader("AAABBB"),
                chunkChars = 3,
                instruction = "",
            )
            val stdinAfter = StdinPromptSource(BufferedReader(StringReader("manual probe\n/exit\n")))

            // when
            SessionLoop(
                cliArgs = chat,
                llmApi = fakeApi,
                historyStore = store,
                promptSource = feedSource,
                replAfterFeed = stdinAfter,
            ).run()

            // then
            // opener + chunk1 (failed) + manual REPL probe = 3 calls.
            // The transition happens despite feedSource.terminated = true:
            // we let the user keep poking the model after the first error.
            assertEquals(3, fakeApi.calls.size)
            assertEquals("manual probe", fakeApi.calls[2].messages.last().text)
        }
    }
    //endregion
}
