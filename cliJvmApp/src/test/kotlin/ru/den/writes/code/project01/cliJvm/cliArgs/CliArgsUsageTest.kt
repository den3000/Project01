package ru.den.writes.code.project01.cliJvm.cliArgs

import ru.den.writes.code.project01.cliJvm.CliArgs
import kotlin.test.Test
import kotlin.test.assertTrue

class CliArgsUsageTest {

    //region public flag coverage

    @Test
    fun `when USAGE is read - then every public flag is mentioned`() {
        // given
        // Light coverage so a future flag-renaming doesn't silently
        // leave USAGE stale.
        val usage = CliArgs.USAGE
        val flags = listOf(
            "-prompt", "-provider", "-maxTokens", "-stopSequence", "-endSequence",
            "-temperature", "-model", "-session", "-oneshot", "-tui",
            "-sessions", "-clean", "-inflate",
            "-feedFile", "-chunkChars", "-feedInstruction", "-byLine",
            "-compress", "-keepLast", "-summarizeEvery", "-strategy",
            "-stageAgent", "-judgeAgent",
        )

        // when - then
        // forEach justified here (rule §11.E): one logical invariant — "USAGE
        // mentions every public flag" — over an extending list. Per-case
        // message makes a failing flag immediately identifiable.
        flags.forEach { flag ->
            assertTrue(usage.contains(flag), "USAGE missing mention of $flag")
        }
    }
    //endregion

    //region provider coverage

    @Test
    fun `when USAGE is read - then every supported provider is listed`() {
        // given
        // Lock in that USAGE keeps every supported provider visible.
        // Future provider drops would silently leave this stale otherwise.
        val usage = CliArgs.USAGE
        val providers = listOf("gemini", "openrouter", "huggingface")

        // when - then
        // forEach justified here (rule §11.E): see above.
        providers.forEach { provider ->
            assertTrue(usage.contains(provider), "USAGE missing provider $provider")
        }
    }
    //endregion
}
