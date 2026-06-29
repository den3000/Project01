package ru.den.writes.code.project01.scheduling

/**
 * The per-tick payload. The engine calls [handle] on every firing and decides what to do
 * by the return value:
 *  - **non-null** → a synchronous result: the engine stores it as a [TaskResult] (and so
 *    makes it visible to [summarize]). This is how openmeteo-mcp's weather tick (returns the
 *    weather text) and cliJvmApp's collect tick (returns an MCP tool's output) work.
 *  - **null** → the handler fired asynchronously — e.g. it enqueued an intent into another
 *    loop whose result arrives later, outside the engine. Nothing to store now; the engine
 *    just reschedules. This is how cliJvmApp's agent tick works.
 *
 * That single bit (store the result or not) is all the engine needs, which keeps it
 * unaware of MCP, the agent's MVI loop, or any concrete data source.
 */
fun interface TaskHandler {
    suspend fun handle(task: ScheduledTask): String?
}
