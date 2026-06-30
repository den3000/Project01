# localfs-mcp

A standalone **MCP (Model Context Protocol) server** over stdio that composes a document
in memory and writes it to the local filesystem. Built on the **Kotlin MCP SDK**
([`io.modelcontextprotocol:kotlin-sdk`](https://github.com/modelcontextprotocol/kotlin-sdk)).
Package `ru.den.writes.code.project01.mcps.localfs`; gradle module `:mcps:localfs-mcp`.
Standalone — no dependency on `:shared`.

Spawned by an MCP client as a subprocess. No modes or flags: `main()` runs the server until
the client disconnects (stdin closes). stdout is the JSON-RPC channel; diagnostics go to stderr.

## Tools

- **`append_to_document`** — input `{ "text": string }`: append a line to the in-memory,
  session-scoped document. Generic — pass any text, e.g. a weather string returned by another
  server's tool.
- **`save_document`** — input `{ "filename"?: string }`: write the accumulated document to
  `~/.project01-localfs/documents/<filename>` (default `document.md`) and return its path.
  The filename is reduced to its base name, so the tool can't write outside the documents dir.

The two tools share one `DocumentStore` (an in-memory buffer guarded by a `Mutex`), so a client
composes a document over several `append_to_document` calls, then flushes it with `save_document`.

## Cross-server pipeline

This server is the filesystem half of the project's MCP orchestration demo. An LLM driving
both servers chains tools across them:

```
current_weather  [openmeteo-mcp]   →   append_to_document  [localfs-mcp]   →   save_document  [localfs-mcp]
```

The weather string from the first server is threaded verbatim into `append_to_document`'s `text`
argument — data flowing **between** servers. See the root README for the two-`-mcpServer` run.

## Run

```bash
./gradlew :mcps:localfs-mcp:installDist
BIN=./mcps/localfs-mcp/build/install/localfs-mcp/bin/localfs-mcp
$BIN          # runs the server on stdio (intended to be spawned by an MCP client)
```

## Layout

All under `src/main/kotlin/ru/den/writes/code/project01/mcps/localfs/`:

| File | Role |
|------|------|
| `main.kt` | Serve-only entry point — `runFileSystemServer()`. |
| `FileSystemServer.kt` | Registers `append_to_document` / `save_document`, wires `StdioServerTransport`, stays alive. |
| `Document.kt` | `DocumentStore` (in-memory buffer) + pure `renderDocument` / `documentFileFor` / `saveDocument`. |

The pure helpers are unit-tested (`DocumentTest`); the live server path is verified by running
the binary.

## Tests

```bash
./gradlew :mcps:localfs-mcp:test
```

Offline and fast — document rendering, filename resolution (path-traversal guard), file writing.
No network, no subprocess.

## Dependencies

`mcp-kotlin-sdk` + `kotlinx-serialization-json` (tool input schemas) + `kotlinx-coroutines-core`.
No ktor engine — the stdio transport rides `kotlinx-io` streams.
