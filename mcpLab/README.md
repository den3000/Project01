# mcpLab

A sandbox for MCP (Model Context Protocol) experiments — a JVM console app
(Kotlin + the Gradle `application` plugin, same shape as `cliJvmApp`; package
`ru.den.writes.code.project01.mcpLab`), kept separate from the LLM CLI. Built on
the official **Kotlin MCP SDK**
([`io.modelcontextprotocol:kotlin-sdk`](https://github.com/modelcontextprotocol/kotlin-sdk),
maintained in collaboration with JetBrains). It has **two modes**:

1. **Client probe** (default) — spawns an existing MCP server as a subprocess,
   connects over **stdio**, calls `listTools` and prints the server's tools. No
   LLM, no API keys.
2. **Server** (`--serve`) — runs *our own* MCP server over stdio with one tool,
   **`current_weather(city)`**, backed by the free Open-Meteo API. This is the
   server the LLM CLI drives through its `-mcpServer` flag (see the root README).

## Run

```bash
./gradlew :mcpLab:installDist
BIN=./mcpLab/build/install/mcpLab/bin/mcpLab

# --- client probe ---
$BIN                                          # default: reference "everything" server (~13 tools)
$BIN npx -y @dangahagan/weather-mcp@latest    # any server: pass its launch command verbatim
$BIN java -jar path/to/server.jar             # a local jar / python / node server works the same

# --- our weather server ---
$BIN --serve                                  # run the Open-Meteo weather MCP server on stdio
$BIN "$BIN --serve"                           # probe spawns our own server → lists current_weather

$BIN -h                                        # help
```

Without `installDist`: `./gradlew :mcpLab:run --args="--serve"` (or any probe args).

## Client probe

The server command is just the argv to spawn — **the probe code is identical for
any MCP server**, only the command differs. No arguments → the reference server.

```
$ mcpLab
Connected to: npx -y @modelcontextprotocol/server-everything
13 tool(s):
  • echo — Echoes back the input string
  • get-sum — Returns the sum of two numbers
  ...
```

Diagnostics (the spawn line, the server's own logs, errors) go to **stderr**;
only `Connected to:` + the tool list go to **stdout**, so it pipes cleanly.

## Weather MCP server (`--serve`)

`mcpLab --serve` turns the module into an MCP **server**: it registers one tool
and serves it over stdio. It is meant to be **spawned by an MCP client**, not run
by hand.

- **Tool:** `current_weather` — input schema `{ "city": string }`, returns a
  one-line summary (place, conditions, temperature, wind).
- **Backed by** the free, key-less Open-Meteo API (`OpenMeteoClient.kt`): geocode
  the city → coordinates, then fetch current weather. Needs network, no auth.
- **Transport:** `StdioServerTransport(System.in…, System.out…)` — stdout is the
  JSON-RPC channel; every log goes to stderr so it can't corrupt the protocol.
- **Lifecycle:** `Server.createSession(transport)` + an `onClose` latch keeps the
  server alive until the client disconnects (stdin closes).

The LLM CLI uses it for function calling: `cliJvmApp -mcpServer "…/mcpLab --serve"`
(root README). You can also point the probe at it:

```bash
BIN=./mcpLab/build/install/mcpLab/bin/mcpLab
$BIN "$BIN --serve"          # → "1 tool(s):  • current_weather — …"
```

## Layout

All under `src/main/kotlin/ru/den/writes/code/project01/mcpLab/`:

| File | Role |
|------|------|
| `main.kt` | Bootstrap + dispatch: `--serve` → the weather server; `-h` → help; else the client probe. |
| `ServerCommand.kt` | `parseServerCommand(args)` + `DEFAULT_SERVER_COMMAND` — pure command resolution. |
| `ToolList.kt` | `ToolInfo` + `formatToolList(tools)` — pure probe-output rendering. |
| `WeatherServer.kt` | `runWeatherServer()` — registers `current_weather`, wires `StdioServerTransport`, stays alive. |
| `OpenMeteoClient.kt` | `currentWeather(city)` over Open-Meteo + pure `formatWeather` / `weatherCodeDescription`. |

Pure functions are unit-tested (`ParseServerCommandTest`, `FormatToolListTest`,
`WeatherFormatTest`). The live `connect` / `listTools` / `callTool` paths are
verified by running the binary — they need a real subprocess.

Probe core:
```kotlin
val process = ProcessBuilder(command).redirectError(Redirect.INHERIT).start()
val transport = StdioClientTransport(process.inputStream.asSource().buffered(),
                                     process.outputStream.asSink().buffered())
val client = Client(clientInfo = Implementation("mcpLab", "0.1.0"))
client.connect(transport)
val tools = client.listTools().tools          // each: .name, .description, .inputSchema
```

Server core (`--serve`):
```kotlin
val server = Server(Implementation("mcpLab-weather", "0.1.0"),
                    ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))))
server.addTool("current_weather", "…", ToolSchema(properties = …, required = listOf("city"))) { request ->
    CallToolResult(listOf(TextContent(openMeteo.currentWeather(city))))   // handler: ClientConnection.(CallToolRequest)
}
val session = server.createSession(StdioServerTransport(System.`in`.asSource().buffered(),
                                                        System.out.asSink().buffered()))
// onClose latch → done.join()  — stays alive until the client disconnects
```

## Requirements

- **JDK 11+** (developed on 21).
- **node / npx** only for the `npx …` example servers in probe mode. Any other
  MCP server works — pass its command. The first `npx` run downloads the package.
- **`--serve`** needs network access to Open-Meteo (free, no key).

## Dependencies

- `io.modelcontextprotocol:kotlin-sdk` — pinned in `gradle/libs.versions.toml`
  (`mcp` / `mcp-kotlin-sdk`). The umbrella artifact pulls client + server + ktor
  and bumps `ktor-client-core`. The stdio transport itself needs no ktor engine
  — it rides `kotlinx-io` streams.
- `ktor-client-java` + content-negotiation + `kotlinx-serialization-json` — the
  `--serve` server uses them to call Open-Meteo over HTTPS.
- `kotlinx-coroutines-core` — `suspend` entrypoints + `withTimeout`.

No dependency on `:shared` — the module is standalone.

## Notes / gotchas

- **Probe shutdown is explicit.** Some servers don't exit when their stdin
  closes, so the SDK's suspend `Client.close()` can block on the stdio reader.
  The probe force-kills the subprocess (`destroyForcibly`) and `exitProcess`es,
  so it always terminates after printing.
- **Bounded probe waits.** `connect` + `listTools` run under a 60 s `withTimeout`;
  an unknown server command fails fast (`IOException` from `start()`).
- **`addTool` handler is a `ClientConnection.(CallToolRequest)` extension lambda**
  — one parameter (`request`), receiver is the connection (not two params). The
  2-arg `StdioServerTransport(in, out)` ctor is deprecated; use the trailing-lambda form.
- A tool's `description` is optional; `formatToolList` prints name-only when blank.

## Tests

```bash
./gradlew :mcpLab:test
```

Offline and fast — pure command-parsing, tool-list rendering, weather formatting.
No network, no subprocess.
