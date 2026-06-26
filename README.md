This is a Kotlin Multiplatform project targeting Android, iOS and Desktop (JVM) with
Compose Multiplatform, plus a JVM console **LLM client** built on a shared, provider-neutral
domain core.

## Modules

- **[/shared](./shared/src)** — code shared across all targets (Android, iOS, Desktop/JVM):
  both the Compose Multiplatform UI and the provider-neutral **LLM domain core** the CLI is
  built on — `llm/` (the `LlmApi` contract, provider catalogs, Gemini/OpenRouter/Hugging Face
  clients), `context/` (rolling-summary compaction), `pricing/`, `memory/` (profile + rules +
  task FSM), `agent/` (one-turn responder), `invariant/` (LLM judge).
  - [commonMain](./shared/src/commonMain/kotlin) is common to all targets; the platform folders
    (e.g. [iosMain](./shared/src/iosMain/kotlin), [jvmMain](./shared/src/jvmMain/kotlin)) hold
    code compiled only for the target named by the folder.
- **[/cliJvmApp](./cliJvmApp)** — the workhorse: a JVM console client that sends a prompt to a
  chat-style LLM (Gemini / OpenRouter / Hugging Face), prints the reply with a token/cost
  footer, and persists a multi-turn REPL to local SQLite. Adds a memory layer
  (profile / rules / task FSM), per-stage agents, an invariant judge, context strategies and
  MCP function calling. Full docs: **[cliJvmApp/README.md](./cliJvmApp/README.md)**.
- **[/mcpLab](./mcpLab)** — a sandbox for MCP (Model Context Protocol) experiments on the
  official Kotlin MCP SDK: a client probe that lists any stdio server's tools, and an own
  Open-Meteo weather server (`--serve`) the CLI drives via `-mcpServer`. Docs:
  [mcpLab/README.md](./mcpLab/README.md).
- **[/scheduling](./scheduling)** — a reusable, dependency-free scheduler core (deferred +
  periodic tasks) meant to sit under both mcpLab and cliJvmApp; integration into those is still
  pending. Docs: [scheduling/README.md](./scheduling/README.md).
- **[/cliTui](./cliTui)** — an isolated sandbox for comparing terminal-UI libraries on the JVM.
  The Kotter + Mordant combo it settled on is already integrated into cliJvmApp (the `-tui`
  view). Docs: [cliTui/README.md](./cliTui/README.md).
- **[/iosApp](./iosApp/iosApp)** — the iOS application entry point (SwiftUI host for the shared
  Compose UI; add SwiftUI code here).
- **[/androidApp](./androidApp)**, **[/desktopApp](./desktopApp)** — the Android and Desktop
  (JVM) Compose Multiplatform application targets.

## Running the apps

Use the run configurations in your IDE's run widget, or these Gradle commands:

- Android app: `./gradlew :androidApp:assembleDebug`
- Desktop app: `./gradlew :desktopApp:run` (hot reload: `./gradlew :desktopApp:hotRun --auto`)
- iOS app: open the [/iosApp](./iosApp) directory in Xcode and run it from there.
- CLI JVM app: `./gradlew :cliJvmApp:installDist`, then
  `./cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp -prompt "<text>" [...flags]` — see
  **[cliJvmApp/README.md](./cliJvmApp/README.md)** for flags, model catalogs and recipes.
- MCP sandbox: `./gradlew :mcpLab:installDist && ./mcpLab/build/install/mcpLab/bin/mcpLab`.

## Running tests

Use the run button in your IDE's editor gutter, or these Gradle tasks:

- Android tests: `./gradlew :shared:testAndroidHostTest`
- Desktop + shared domain-core tests (LLM API, pricing, context, memory): `./gradlew :shared:jvmTest`
- iOS tests: `./gradlew :shared:iosSimulatorArm64Test`
- CLI JVM app tests (fast, no network — providers stubbed via `FakeLlmApi`): `./gradlew :cliJvmApp:test`
- MCP sandbox tests (offline): `./gradlew :mcpLab:test`

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html),
[Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/#compose-multiplatform),
[Kotlin/Wasm](https://kotl.in/wasm/)…
