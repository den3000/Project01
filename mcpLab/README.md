# mcpLab

A minimal **MCP (Model Context Protocol) client** and a sandbox for MCP
experiments — kept deliberately separate from the LLM CLI (`cliJvmApp`). It is a
JVM console app (Kotlin + the Gradle `application` plugin, same shape as
`cliJvmApp`; package `ru.den.writes.code.project01.mcpLab`) that:

1. launches an existing MCP server as a subprocess and connects to it over **stdio**,
2. calls **`listTools`** and prints the tools the server advertises.

That is the whole job — prove a connection can be established and a tool list
retrieved. It does **not** call an LLM and needs **no API keys**.

Built on the official **Kotlin MCP SDK** —
[`io.modelcontextprotocol:kotlin-sdk`](https://github.com/modelcontextprotocol/kotlin-sdk),
maintained in collaboration with JetBrains.

## Run

```bash
./gradlew :mcpLab:installDist

# Default: the upstream reference "everything" server (no key, ~13 demo tools)
./mcpLab/build/install/mcpLab/bin/mcpLab

# Any server: pass its launch command verbatim. Example weather server
# (NOAA / Open-Meteo, no key, ~9 real tools):
./mcpLab/build/install/mcpLab/bin/mcpLab npx -y @dangahagan/weather-mcp@latest

# A local jar / python / node server works the same way:
./mcpLab/build/install/mcpLab/bin/mcpLab java -jar path/to/server.jar

# Help
./mcpLab/build/install/mcpLab/bin/mcpLab -h
```

Without `installDist`:
`./gradlew :mcpLab:run --args="npx -y @dangahagan/weather-mcp@latest"`.

The server command is simply the argv to spawn — **the client code is identical
for any MCP server**, only the command differs. With no arguments it falls back
to the reference server.

### Example output

```
$ mcpLab
Connected to: npx -y @modelcontextprotocol/server-everything
13 tool(s):
  • echo — Echoes back the input string
  • get-sum — Returns the sum of two numbers
  • get-tiny-image — Returns a tiny MCP logo image.
  ...

$ mcpLab npx -y @dangahagan/weather-mcp@latest
Connected to: npx -y @dangahagan/weather-mcp@latest
9 tool(s):
  • get_forecast — Get future weather forecast for a location (global coverage) ...
  • get_current_conditions — Get the most recent weather observation ...
  • get_alerts — Get active weather alerts, watches, warnings, and advisories ...
  ...
```

Diagnostics — the spawn line, the server's own logs, and errors — go to
**stderr**; only `Connected to:` plus the tool list go to **stdout**, so the
output can be piped cleanly.

## Layout

All under `src/main/kotlin/ru/den/writes/code/project01/mcpLab/`:

| File | Role |
|------|------|
| `main.kt` | Bootstrap: parse args → spawn subprocess → `StdioClientTransport` → `Client.connect` → `listTools` → print. |
| `ServerCommand.kt` | `parseServerCommand(args)` + `DEFAULT_SERVER_COMMAND` — pure command resolution (no args → reference server). |
| `ToolList.kt` | `ToolInfo` + `formatToolList(tools)` — pure stdout rendering. |

The two pure functions are unit-tested in `src/test/...` (`ParseServerCommandTest`,
`FormatToolListTest`). The live connect / `listTools` path is verified by actually
running the binary (above), not by a unit test — it needs a real subprocess.

The core wiring, end to end:

```kotlin
val process = ProcessBuilder(command)
    .redirectError(ProcessBuilder.Redirect.INHERIT)        // server logs → our stderr
    .start()
val transport = StdioClientTransport(
    input  = process.inputStream.asSource().buffered(),    // kotlinx-io
    output = process.outputStream.asSink().buffered(),
)
val client = Client(clientInfo = Implementation(name = "mcpLab", version = "0.1.0"))
client.connect(transport)
val tools = client.listTools().tools                       // each: .name, .description, .inputSchema
```

## Transport

Only **stdio** is wired (spawn a server, talk over stdin/stdout) — the simplest,
self-contained option for a demo: no network endpoint, no auth. The SDK also
ships `SseClientTransport`, `StreamableHttpClientTransport` and
`WebSocketClientTransport`; pointing `mcpLab` at a hosted server — e.g. the
reference one at `https://example-server.modelcontextprotocol.io/mcp` over
HTTP/SSE — would be a small extension (add a ktor engine, swap the transport).

## Requirements

- **JDK 11+** (developed on 21).
- **node / npx** only for the `npx …` example servers. Any other MCP server
  works too — pass its command (`java -jar …`, `python3 …`, a local binary). The
  first `npx` run downloads the package (network); later runs use the npm cache.

## Dependencies

- `io.modelcontextprotocol:kotlin-sdk` — pinned in `gradle/libs.versions.toml`
  (`mcp` / `mcp-kotlin-sdk`). The umbrella artifact pulls the client, server and
  ktor, and bumps `ktor-client-core` to its own required version. For the stdio
  transport no ktor **engine** is needed — the transport rides `kotlinx-io`
  streams, pulled transitively.
- `kotlinx-coroutines-core` — `suspend` entrypoint + `withTimeout`.

No dependency on `:shared` — the module is intentionally standalone.

## Notes / gotchas

- **Shutdown is explicit.** Some MCP servers don't exit when their stdin closes,
  so the SDK's suspend `Client.close()` can block forever on the stdio reader.
  `mcpLab` instead force-kills the subprocess (`process.destroyForcibly()`) and
  ends with `exitProcess`, so the JVM always terminates after printing — even
  with the transport's background reader still alive.
- **Bounded waits.** `connect` + `listTools` run under a 60 s `withTimeout`, and
  an unknown server command fails fast (`IOException` from
  `ProcessBuilder.start()`). A server that starts but never speaks MCP can't hang
  the probe indefinitely.
- **No LLM, no tokens, no keys.** Only `listTools` is called (never `callTool`),
  and the example servers are free / no-auth.
- A tool's `description` is optional; `formatToolList` prints name-only when it
  is blank or absent.

## Tests

```bash
./gradlew :mcpLab:test
```

Offline and fast — they cover pure command-parsing and tool-list rendering. No
network, no subprocess.
