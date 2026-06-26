package ru.den.writes.code.project01.cliJvm.clicontrols.grammar

import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.BRANCH
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.CLEAR
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.NOTE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.PAUSE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.PROFILE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.RESUME
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.RULE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.SESSION
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.SHOW
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.STYLE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.SWITCH
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.TASK
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsParser
import ru.den.writes.code.project01.cliJvm.clicontrols.ExpectedControl
import ru.den.writes.code.project01.cliJvm.clicontrols.ParseError
import ru.den.writes.code.project01.cliJvm.clicontrols.Surface.CMD
import ru.den.writes.code.project01.cliJvm.clicontrols.Surface.FLAG
import ru.den.writes.code.project01.cliJvm.clicontrols.assertMatchParserCmd
import ru.den.writes.code.project01.cliJvm.clicontrols.assertMatchParserError
import ru.den.writes.code.project01.cliJvm.clicontrols.assertMatchParserFlag
import ru.den.writes.code.project01.cliJvm.clicontrols.sub
import ru.den.writes.code.project01.cliJvm.clicontrols.toArgsList
import ru.den.writes.code.project01.cliJvm.clicontrols.top
import kotlin.test.Test

class CliEntityGrammarTest {

    //region rule
    @Test
    fun `when rule command grammar used - then it is parsed accordingly`() {
        // given
        val cli = RULE
        val sfc = CMD
        val cmd = "/rule"
        val arg1 = "some content"
        val arg2 = "taskId" // string

        // when
        val parser = CliControlsParser()

        // then
        assertMatchParserCmd("$cmd", ExpectedControl(surface = sfc, arg = cli), parser)
        assertMatchParserCmd("$cmd \"$arg1\"", ExpectedControl(surface = sfc, arg = cli, value = arg1), parser)
        assertMatchParserCmd("$cmd show", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, SHOW))), parser)
        assertMatchParserCmd("$cmd show $arg2", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, SHOW, value = arg2))), parser)
        assertMatchParserCmd("$cmd clear", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, CLEAR))), parser)
        assertMatchParserCmd("$cmd clear $arg2", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, CLEAR, value = arg2))), parser)
    }

    @Test
    fun `when rule flag grammar used - then it is parsed accordingly`() {
        // given
        val cli = RULE
        val sfc = FLAG
        val cmd = "-rule"
        val arg1 = "some content"
        val arg2 = "taskId" // string

        // when
        val parser = CliControlsParser()

        // then
        assertMatchParserFlag("$cmd".toArgsList(), top(cli, sfc), parser)
        assertMatchParserFlag("$cmd \"$arg1\"".toArgsList(), top(cli, sfc, value = arg1), parser)
        assertMatchParserFlag("$cmd show".toArgsList(), top(cli, sfc, subs = listOf(sub(cli, SHOW))), parser)
        assertMatchParserFlag("$cmd show $arg2".toArgsList(), top(cli, sfc, subs = listOf(sub(cli, SHOW, value = arg2))), parser)
        assertMatchParserFlag("$cmd clear".toArgsList(), top(cli, sfc, subs = listOf(sub(cli, CLEAR))), parser)
        assertMatchParserFlag("$cmd clear $arg2".toArgsList(), top(cli, sfc, subs = listOf(sub(cli, CLEAR, value = arg2))), parser)

        assertMatchParserError("$cmd $arg1".toArgsList(), ParseError.UnexpectedToken("content"), parser)
    }
    //endregion

    //region branch
    @Test
    fun `when branch command grammar used - then it is parsed accordingly`() {
        // given
        val cli = BRANCH
        val sfc = CMD
        val cmd = "/branch"
        val arg = "branch_name"

        // when
        val parser = CliControlsParser()

        // then
        assertMatchParserCmd("$cmd", ExpectedControl(surface = sfc, arg = cli), parser)
        assertMatchParserCmd("$cmd $arg", ExpectedControl(surface = sfc, arg = cli, value = arg), parser)
        assertMatchParserCmd("$cmd show", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, SHOW))), parser)
        assertMatchParserCmd("$cmd show $arg", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, SHOW, value = arg))), parser)
        assertMatchParserCmd("$cmd clear", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, CLEAR))), parser)
        assertMatchParserCmd("$cmd clear $arg", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, CLEAR, value = arg))), parser)

        assertMatchParserCmd("$cmd switch $arg", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, SWITCH, value = arg))), parser)
        assertMatchParserError(CMD, "$cmd switch", ParseError.MissingValue(SWITCH), parser)
    }
    //endregion

    //region session
    @Test
    fun `when session command grammar used - then it is parsed accordingly`() {
        // given
        val cli = SESSION
        val sfc = CMD
        val cmd = "/session"
        val name = "demo"

        // when
        val parser = CliControlsParser()

        // then — in-session: bare lists, show/clear take an optional name; the select VALUE is startup-only
        assertMatchParserCmd("$cmd", ExpectedControl(surface = sfc, arg = cli), parser)
        assertMatchParserCmd("$cmd show", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, SHOW))), parser)
        assertMatchParserCmd("$cmd show $name", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, SHOW, value = name))), parser)
        assertMatchParserCmd("$cmd clear", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, CLEAR))), parser)
        assertMatchParserCmd("$cmd clear $name", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, CLEAR, value = name))), parser)
        assertMatchParserError(CMD, "$cmd $name", ParseError.ValueNotAllowedHere(cli, CMD), parser)
    }

    @Test
    fun `when session flag grammar used - then it is parsed accordingly`() {
        // given
        val cli = SESSION
        val sfc = FLAG
        val cmd = "-session"
        val name = "demo"

        // when
        val parser = CliControlsParser()

        // then — at startup the select value IS allowed
        assertMatchParserFlag("$cmd".toArgsList(), top(cli, sfc), parser)
        assertMatchParserFlag("$cmd $name".toArgsList(), top(cli, sfc, value = name), parser)
        assertMatchParserFlag("$cmd show".toArgsList(), top(cli, sfc, subs = listOf(sub(cli, SHOW))), parser)
        assertMatchParserFlag("$cmd show $name".toArgsList(), top(cli, sfc, subs = listOf(sub(cli, SHOW, value = name))), parser)
        assertMatchParserFlag("$cmd clear".toArgsList(), top(cli, sfc, subs = listOf(sub(cli, CLEAR))), parser)
        assertMatchParserFlag("$cmd clear $name".toArgsList(), top(cli, sfc, subs = listOf(sub(cli, CLEAR, value = name))), parser)
    }
    //endregion

    //region profile
    @Test
    fun `when profile command grammar used - then it is parsed accordingly`() {
        // given
        val cli = PROFILE
        val sfc = CMD
        val cmd = "/profile"
        val name = "work"
        val text = "terse and short"

        // when
        val parser = CliControlsParser()

        // then — name is the value; a section nests with optional text (absent = clear that section)
        assertMatchParserCmd("$cmd", ExpectedControl(surface = sfc, arg = cli), parser)
        assertMatchParserCmd("$cmd $name", ExpectedControl(surface = sfc, arg = cli, value = name), parser)
        assertMatchParserCmd("$cmd show", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, SHOW))), parser)
        assertMatchParserCmd("$cmd show $name", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, SHOW, value = name))), parser)
        assertMatchParserCmd("$cmd clear", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, CLEAR))), parser)
        assertMatchParserCmd("$cmd clear $name", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, CLEAR, value = name))), parser)
        assertMatchParserCmd("$cmd $name style \"$text\"", ExpectedControl(surface = sfc, arg = cli, value = name, subs = listOf(sub(cli, STYLE, value = text))), parser)
    }

    @Test
    fun `when profile flag grammar used - then it is parsed accordingly`() {
        // given
        val cli = PROFILE
        val sfc = FLAG
        val cmd = "-profile"
        val name = "work"
        val text = "terse and short"

        // when
        val parser = CliControlsParser()

        // then
        assertMatchParserFlag("$cmd".toArgsList(), top(cli, sfc), parser)
        assertMatchParserFlag("$cmd $name".toArgsList(), top(cli, sfc, value = name), parser)
        assertMatchParserFlag("$cmd show $name".toArgsList(), top(cli, sfc, subs = listOf(sub(cli, SHOW, value = name))), parser)
        assertMatchParserFlag("$cmd clear".toArgsList(), top(cli, sfc, subs = listOf(sub(cli, CLEAR))), parser)
        assertMatchParserFlag("$cmd $name style \"$text\"".toArgsList(), top(cli, sfc, value = name, subs = listOf(sub(cli, STYLE, value = text))), parser)
    }
    //endregion

    //region task
    @Test
    fun `when task command grammar used - then it is parsed accordingly`() {
        // given
        val cli = TASK
        val sfc = CMD
        val cmd = "/task"
        val id = "auth"
        val note = "did x"

        // when
        val parser = CliControlsParser()

        // then — pause/resume/note nest under the active task id
        assertMatchParserCmd("$cmd", ExpectedControl(surface = sfc, arg = cli), parser)
        assertMatchParserCmd("$cmd $id", ExpectedControl(surface = sfc, arg = cli, value = id), parser)
        assertMatchParserCmd("$cmd show", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, SHOW))), parser)
        assertMatchParserCmd("$cmd clear $id", ExpectedControl(surface = sfc, arg = cli, subs = listOf(sub(cli, CLEAR, value = id))), parser)
        assertMatchParserCmd("$cmd $id pause", ExpectedControl(surface = sfc, arg = cli, value = id, subs = listOf(sub(cli, PAUSE))), parser)
        assertMatchParserCmd("$cmd $id resume", ExpectedControl(surface = sfc, arg = cli, value = id, subs = listOf(sub(cli, RESUME))), parser)
        assertMatchParserCmd("$cmd $id note \"$note\"", ExpectedControl(surface = sfc, arg = cli, value = id, subs = listOf(sub(cli, NOTE, value = note))), parser)
        assertMatchParserError(CMD, "$cmd $id note", ParseError.MissingValue(NOTE), parser)
    }

    @Test
    fun `when task flag grammar used - then it is parsed accordingly`() {
        // given
        val cli = TASK
        val sfc = FLAG
        val cmd = "-task"
        val id = "auth"
        val note = "did x"

        // when
        val parser = CliControlsParser()

        // then
        assertMatchParserFlag("$cmd $id".toArgsList(), top(cli, sfc, value = id), parser)
        assertMatchParserFlag("$cmd $id pause".toArgsList(), top(cli, sfc, value = id, subs = listOf(sub(cli, PAUSE))), parser)
        assertMatchParserFlag("$cmd $id note \"$note\"".toArgsList(), top(cli, sfc, value = id, subs = listOf(sub(cli, NOTE, value = note))), parser)
        assertMatchParserError("$cmd $id note".toArgsList(), ParseError.MissingValue(NOTE), parser)
    }
    //endregion
}