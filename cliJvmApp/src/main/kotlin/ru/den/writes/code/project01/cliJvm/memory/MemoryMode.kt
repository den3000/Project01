package ru.den.writes.code.project01.cliJvm.memory

/**
 * How the memory layer (profile + rules + active task) is woven into the
 * outgoing request.
 *
 * The two modes exist side by side so a Day-11 demo can ship the same
 * prompt through both and compare the model's behaviour:
 *
 * - [PREAMBLE] — one synthetic USER → ASSISTANT pair planted at the top of
 *   the wire list, mimicking the existing sticky-facts / rolling-summary
 *   shape. Works with every provider without changes — the bytes stay
 *   `Role.USER`/`Role.ASSISTANT`.
 * - [SYSTEM] — emit dedicated `Role.SYSTEM` messages; each provider lifts
 *   them into its native system slot (Gemini → `SystemInstruction`,
 *   OpenAI-shape → `role:"system"`). Semantically cleaner, but takes the
 *   path through the patched providers.
 *
 * Switchable per-session via the `-memory-mode` CLI flag and `/memory-mode`
 * REPL command.
 */
internal enum class MemoryMode { PREAMBLE, SYSTEM }
