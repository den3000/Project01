package ru.den.writes.code.agenticHub.cliJvm.commandMappers

import ru.den.writes.code.agenticHub.cliJvm.cliargs.ParseError
import ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand
import ru.den.writes.code.agenticHub.features.rag.embedding.EmbedderKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * rag entity: startup `-rag add … src …` builds an index (admin), bare `-rag <name>`
 * preloads one into the session. The two share a head and are told apart by the `add`
 * sub — this pins that split.
 */
class CliArgsToStartCommandMapperRagTest {

    //region index (admin)
    @Test
    fun `when rag add is used - then it maps to RagAdd with the chosen embedder`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()
        val cases = listOf(
            "-rag add docs src /tmp/proj" to StartCommand.RagAdd("docs", "/tmp/proj", EmbedderKind.OLLAMA),
            "-rag add docs src /tmp/proj embedder ollama" to
                StartCommand.RagAdd("docs", "/tmp/proj", EmbedderKind.OLLAMA),
            "-rag add docs src /tmp/proj embedder gemini" to
                StartCommand.RagAdd("docs", "/tmp/proj", EmbedderKind.GEMINI),
        )

        // when - then
        cases.forEach { (input, expected) ->
            assertEquals(expected, mapper.parseOk(input.toArgsArray()), input)
        }
    }

    @Test
    fun `when rag add runs under an explicit gemini provider - then the embedder follows it`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()

        // when
        val command = mapper.parseOk("-rag add docs src /tmp/proj -agent provider gemini".toArgsArray())

        // then
        assertEquals(EmbedderKind.GEMINI, assertIs<StartCommand.RagAdd>(command).embedder)
    }

    @Test
    fun `when rag add lacks its source - then the missing part is reported`() {
        // given — a missing part prints USAGE, so it must stay a MissingArg
        val mapper = createCliArgsToStartCommandMapper()

        // when - then
        assertIs<ParseError.MissingArg>(mapper.parseErr("-rag add docs".toArgsArray()))
    }

    @Test
    fun `when rag add omits the index name - then the stray source path is rejected`() {
        // given — "src" is eaten as add's own value, leaving the path attached to nothing
        val mapper = createCliArgsToStartCommandMapper()

        // when - then
        assertIs<ParseError.UnexpectedToken>(mapper.parseErr("-rag add src /tmp/proj".toArgsArray()))
    }
    //endregion

    //region preload (session)
    @Test
    fun `when rag names an index alongside a prompt - then the session preloads it`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()

        // when
        val command = mapper.parseOk("-prompt \"review this\" -rag projdocs".toArgsArray())

        // then
        assertEquals("projdocs", assertIs<StartCommand.RunChat>(command).config.ragPreload)
    }

    @Test
    fun `when preloading under an explicit gemini provider - then the query embedder is gemini`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()

        // when
        val command = mapper.parseOk(
            "-prompt \"review this\" -rag projdocs -agent provider gemini".toArgsArray(),
        )

        // then — index and query must share an embedder; the explicit provider picks it
        val config = assertIs<StartCommand.RunChat>(command).config
        assertEquals("projdocs", config.ragPreload)
        assertEquals(EmbedderKind.GEMINI, config.ragEmbedder)
    }

    @Test
    fun `when no rag flag is given - then nothing is preloaded`() {
        // given
        val mapper = createCliArgsToStartCommandMapper()

        // when
        val command = mapper.parseOk("-prompt \"hi\"".toArgsArray())

        // then
        assertNull(assertIs<StartCommand.RunChat>(command).config.ragPreload)
    }

    @Test
    fun `when rag names an index without a prompt - then the prompt is reported missing`() {
        // given — a preload only means something for a session; alone it is not a command
        val mapper = createCliArgsToStartCommandMapper()

        // when
        val error = mapper.parseErr("-rag projdocs".toArgsArray())

        // then
        assertIs<ParseError.MissingArg>(error)
    }

    @Test
    fun `when rag is combined with oneshot - then rejected`() {
        // given — oneshot has no session to hold an index
        val mapper = createCliArgsToStartCommandMapper()

        // when - then
        mapper.assertInvalid("-prompt \"hi\" -oneshot -rag projdocs".toArgsArray())
    }
    //endregion
}
