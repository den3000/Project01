# openmeteo-mcp

A standalone **MCP (Model Context Protocol) server** over stdio that exposes weather
tools, built on the official **Kotlin MCP SDK**
([`io.modelcontextprotocol:kotlin-sdk`](https://github.com/modelcontextprotocol/kotlin-sdk)).
Package `ru.den.writes.code.project01.mcps.openmeteo`; gradle module `:mcps:openmeteo-mcp`.
Standalone — no dependency on `:shared` (the LLM stack is not pulled in).

It is meant to be **spawned by an MCP client** (e.g. the project's `cliJvmApp -mcpServer`)
as a subprocess. There are no modes or flags: `main()` just runs the server until the
client disconnects (stdin closes). stdout is the JSON-RPC channel; every diagnostic goes
to stderr so it can't corrupt the protocol stream.

## Tools

- **`current_weather`** — input `{ "city": string }`, returns a one-line summary
  (place, conditions, temperature, wind) via the free, key-less Open-Meteo API
  (`OpenMeteoClient.kt`: geocode the city → coordinates, then fetch current weather).
- **`schedule_task`** — input `{ "city": string, "after_seconds"?: int, "every_seconds"?: int }`
  (exactly one of after/every, positive): schedule a one-shot or periodic weather collection.
- **`list_tasks`** (no input) / **`cancel_task`** (`{ "id": string }`) — list every task, or
  cancel an active one.
- **`report`** (no input) — an aggregated summary of the data collected so far
  (count, time span, latest). Reads stored results only — **calls no model**.

## Scheduler

The four scheduling tools delegate to a `SchedulerEngine` from `:scheduling`. While a client
is connected, a background ticker fires due tasks and a reporter logs the summary to stderr
periodically (both on `Dispatchers.IO`, cancelled on disconnect). State (tasks + results) is
JSON-persisted under `~/.project01-mcplab/schedule.json`, so it survives a restart.

## Run

```bash
./gradlew :mcps:openmeteo-mcp:installDist
BIN=./mcps/openmeteo-mcp/build/install/openmeteo-mcp/bin/openmeteo-mcp
$BIN          # runs the server on stdio (intended to be spawned by an MCP client)
```

The LLM CLI uses it for function calling:
`cliJvmApp -mcpServer "$(pwd)/mcps/openmeteo-mcp/build/install/openmeteo-mcp/bin/openmeteo-mcp"`
(see the root README and `cliJvmApp/README.md`). It also pairs with `mcps/localfs-mcp` for a
cross-server document pipeline.

## Layout

All under `src/main/kotlin/ru/den/writes/code/project01/mcps/openmeteo/`:

| File | Role |
|------|------|
| `main.kt` | Serve-only entry point — `runWeatherServer()`. |
| `WeatherServer.kt` | Registers all five tools, runs the scheduler loops, wires `StdioServerTransport`, stays alive. |
| `OpenMeteoClient.kt` | `currentWeather(city)` over Open-Meteo + pure `formatWeather` / `weatherCodeDescription`. |
| `WeatherTaskHandler.kt` | `TaskHandler` for the scheduler: looks up weather for a task's label (a city). |
| `SchedulingTools.kt` | Pure `scheduleFromArgs` / `renderTask` / `renderTasks` + the `buildWeatherScheduler` factory. |

Pure functions are unit-tested (`WeatherFormatTest`, `WeatherTaskHandlerTest`,
`SchedulingToolsTest`). The live server path is verified by running the binary.

## Tests

```bash
./gradlew :mcps:openmeteo-mcp:test
```

Offline and fast — pure weather formatting, scheduling argument parsing/rendering. No network,
no subprocess.

## Notes / gotchas

- **`addTool` handler is a `ClientConnection.(CallToolRequest)` extension lambda** — one
  parameter (`request`), receiver is the connection. The 2-arg `StdioServerTransport(in, out)`
  ctor is deprecated; use the trailing-lambda form.
- **Lifecycle:** `Server.createSession(transport)` + an `onClose` latch (`done.join()`) keeps the
  server alive until the client disconnects.
- **Requirements:** JDK 11+ (developed on 21); network access to Open-Meteo (free, no key).
