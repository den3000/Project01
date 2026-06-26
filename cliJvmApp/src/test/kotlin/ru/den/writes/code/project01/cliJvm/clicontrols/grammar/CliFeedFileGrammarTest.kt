package ru.den.writes.code.project01.cliJvm.clicontrols.grammar

import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.BY_LINE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.CHUNK_CHARS
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.FEED_FILE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.FEED_INSTRUCTION
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsParser
import ru.den.writes.code.project01.cliJvm.clicontrols.ParseError
import ru.den.writes.code.project01.cliJvm.clicontrols.Surface.CMD
import ru.den.writes.code.project01.cliJvm.clicontrols.Surface.FLAG
import ru.den.writes.code.project01.cliJvm.clicontrols.assertMatchParserError
import ru.den.writes.code.project01.cliJvm.clicontrols.assertMatchParserFlag
import ru.den.writes.code.project01.cliJvm.clicontrols.sub
import ru.den.writes.code.project01.cliJvm.clicontrols.toArgsList
import ru.den.writes.code.project01.cliJvm.clicontrols.top
import kotlin.test.Test

/**
 * `feedFile` (startup-only) and its subs chunkChars / byLine / feedInstruction.
 * The byLine⊥chunkChars reciprocal exclude is argv-level — see the crossvalidation
 * suite; here each sub is shown on its own.
 */
class CliFeedFileGrammarTest {

    //region feedFile
    @Test
    fun `when feedFile flag grammar used - then it is parsed accordingly`() {
        // given
        val cli = FEED_FILE
        val sfc = FLAG
        val cmd = "-feedFile"
        val path = "doc.txt"

        // when
        val parser = CliControlsParser()

        // then
        assertMatchParserFlag("$cmd $path".toArgsList(), top(cli, sfc, value = path), parser)
        assertMatchParserFlag("$cmd $path chunkChars 3000".toArgsList(), top(cli, sfc, value = path, subs = listOf(sub(cli, CHUNK_CHARS, value = "3000"))), parser)
        assertMatchParserFlag("$cmd $path byLine".toArgsList(), top(cli, sfc, value = path, subs = listOf(sub(cli, BY_LINE))), parser)
        assertMatchParserFlag("$cmd $path feedInstruction \"prefix:\"".toArgsList(), top(cli, sfc, value = path, subs = listOf(sub(cli, FEED_INSTRUCTION, value = "prefix:"))), parser)
        // negatives
        assertMatchParserError("$cmd".toArgsList(), ParseError.MissingValue(FEED_FILE), parser)
        assertMatchParserError("$cmd $path chunkChars 0".toArgsList(), ParseError.BadValue(CHUNK_CHARS, "0", "an integer >= 1"), parser)
        assertMatchParserError(CMD, "/feedFile $path", ParseError.WrongSurface("feedFile", CMD), parser)
        assertMatchParserError("-chunkChars 9".toArgsList(), ParseError.WrongSurface("chunkChars", FLAG), parser)
    }
    //endregion
}
