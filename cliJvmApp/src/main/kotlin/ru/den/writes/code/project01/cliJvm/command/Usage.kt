package ru.den.writes.code.project01.cliJvm.command

/**
 * One-line-ish usage hint printed alongside a startup error. Hand-written against
 * the clicontrols grammar (a future task could generate it from
 * `CliControls.all` + `ControlSpec.usage`). Generation parity isn't pinned, so
 * keep this in step with the catalog by hand for now.
 */
internal const val USAGE: String =
    "Usage: -prompt <text> [-tui] [-session <name>]\n" +
        "       [-agent [<name>] provider <gemini|openrouter|huggingface> model <id> maxTokens <int>\n" +
        "               temperature <0.0..2.0> stopSequence \"<words>\" endSequence \"<text>\"\n" +
        "               profile <name> mode <none|system|preamble> stages <from..to> judge]\n" +
        "         (knobs live under -agent; repeat -agent with `stages`/`judge` for per-stage agents)\n" +
        "       [-feedFile <path> [chunkChars <int> | byLine] [feedInstruction \"<text>\"]]\n" +
        "       [-strategy <full|window|facts|summary> [keepLast <int>] [summarizeEvery <int>]]\n" +
        "       [-mcpServer \"<command>\"]   (spawn an MCP server, e.g. \"mcpLab --serve\")\n" +
        "   or: -prompt <text> -oneshot [-agent provider <…> model <…> maxTokens <int> …]   (one prompt → reply → exit)\n" +
        "   or: -session                          (list saved sessions)\n" +
        "   or: -session clear [<name>]           (delete one session, or ALL history when bare)\n" +
        "   or: -inflate <N> -session <name>      (duplicate the last N rows of <name>; no LLM)\n" +
        "   or: -memory                           (show the active memory layer)\n" +
        "   or: -profile [<name>] [<section> \"<text>\"] | show <name> | clear [<name>]\n" +
        "   or: -rule \"<text>\" | clear [<id>]\n" +
        "   or: -task <id> [pause | resume] | clear [<id>]\n" +
        "       (profile sections: style | format | constraints | context;\n" +
        "        unnamed profile = profile.md; named profiles under profiles/<name>.md; no LLM)\n" +
        "REPL: /branch [<name> | switch <name> | show], /memory, /agent mode <preamble|system>,\n" +
        "      /profile [<name>] [<section> \"<text>\"] | show <name> | clear [<name>],\n" +
        "      /rule \"<text>\" | clear [<id>], /task <id> | note \"<text>\" | pause | resume | clear [<id>], /reuse, /exit.\n" +
        "Default provider is gemini. Defaults: chunkChars=2500, keepLast=6, summarizeEvery=10."
