# :scheduling — reusable scheduler core

A lightweight, portable core for **deferred (one-shot)** and **periodic** background
tasks: a task model, "when is it due" math, a tick engine, persistence, and result
aggregation. Pure Kotlin — it knows nothing about MCP, the agent's MVI loop, LLMs,
or any concrete data source. Everything domain-specific is injected.

## Why a separate module

The same scheduler is needed in two places that must not duplicate it:

- **openmeteo-mcp** (an MCP server) — exposes scheduling *tools* and collects data in the
  background while it runs.
- **cliJvmApp** (the agent) — polls an MCP tool on a schedule and summarises on its
  own side.

Both depend on this one module and sit on top of it **additively**
(`implementation(projects.scheduling)`). The core stays dependency-free (no `:shared`),
so openmeteo-mcp can use it without pulling in the LLM stack — preserving its standalone build.

## API

- **`Schedule`** — `After(delayMs)` (one-shot / reminder) or `Every(intervalMs)`
  (periodic). Sealed, so new kinds (e.g. cron) add without touching existing ones.
- **`ScheduledTask` / `TaskStatus` (ACTIVE/DONE/CANCELLED) / `TaskResult`** —
  `@Serializable` data model, zero behaviour.
- **Schedule math** (pure, clock-injected — the core never reads the clock itself):
  `Schedule.nextRunAt(anchor)`, `ScheduledTask.isDue(now)`, `ScheduledTask.advance(now)`.
- **`TaskHandler`** — `suspend (ScheduledTask) -> String?`, the per-tick payload.
  Non-null = a synchronous result the engine stores; `null` = the handler fired
  asynchronously (nothing to store right now).
- **`ScheduleStore`** — the persistence seam: `InMemoryScheduleStore` +
  `JsonFileScheduleStore` (atomic temp-then-rename write; a corrupt/absent file reads
  back as empty state).
- **`SchedulerEngine`** — `add` / `cancel` / `list` / `tick` / `runLoop(tickMs)` /
  `summary`, all guarded by a `Mutex`. It does **not** pick a Dispatcher or create a
  scope — the caller runs `runLoop` in whatever context it chooses.
- **`summarize(results)`** — count + time range + latest text.

## Integration (done)

**openmeteo-mcp:** `WeatherTaskHandler` over `OpenMeteoClient`; MCP tools
`schedule_task` / `list_tasks` / `cancel_task` / `report` delegate to
`engine.add` / `list` / `cancel` / `summary`; `runLoop` + a periodic summary on
`Dispatchers.IO`, cancelled on `session.onClose`; `JsonFileScheduleStore` so tasks
survive a server restart. See [`mcps/openmeteo-mcp/README.md`](../mcps/openmeteo-mcp/README.md).

**cliJvmApp:** `CliTaskHandler` routes a fired task by id — `collect` calls an MCP
tool directly via `McpToolClient.execute` (token-free) and returns its text; `agent`
injects a `UiIntent.Submit` into the serialized MVI loop and returns `null`. Driven by the
`-schedule` flag plus REPL `/schedule` (add · list · `clear [<id>]`); the periodic report
is a feed line, posted only when it changes. Per-session `InMemoryScheduleStore`. See
[`cliJvmApp/README.md`](../cliJvmApp/README.md).
