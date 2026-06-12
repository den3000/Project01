This is a Kotlin Multiplatform project targeting Android, iOS, Desktop (JVM).

* [/iosApp](./iosApp/iosApp) contains an iOS application. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/shared](./shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./shared/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./shared/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./shared/src/jvmMain/kotlin)
    folder is the appropriate location.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Android app: `./gradlew :androidApp:assembleDebug`
- Desktop app:
  - Hot reload: `./gradlew :desktopApp:hotRun --auto`
  - Standard run: `./gradlew :desktopApp:run`
- iOS app: open the [/iosApp](./iosApp) directory in Xcode and run it from there.
- CLI JVM app — sends a prompt to a chat-style LLM (Gemini or OpenRouter),
  prints the response with a stats footer (wall-clock duration + token
  counts: `prompt`, `output`, `thoughts` for Gemini's thinking models,
  `total`), then — unless `-oneshot` is passed — drops into a REPL where
  each new line becomes the next prompt. The full conversation is sent
  on every turn (the chat APIs are stateless), so multi-turn context is
  preserved. Successful turns are persisted to a local SQLite file at
  `~/.project01-cli/history.db` (single file, sessions live in a
  `session_id` column), so closing and reopening the app picks up where
  you left off. Recognised REPL commands:
  - `/quit` or `/exit` (or Ctrl-D) — leave the REPL.
  - `/reuse` — feed the model's last reply back as the next prompt without
    retyping it. Handy for chain-of-thought follow-ups where you want the
    model to keep building on what it just said.
  - Run: `./gradlew :cliJvmApp:run --args="-prompt <text> [...flags]"`
  - Build a packaged launcher: `./gradlew :cliJvmApp:installDist`,
    then run `./cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp -prompt "<text>"`
  - API keys are **not** CLI arguments. They are read at build time from
    `local.properties` (gitignored) or from same-named environment
    variables, and exposed via `BuildKonfig`:
    - `GEMINI_API_KEY` → `BuildKonfig.GEMINI_API_KEY`
    - `OPENROUTER_API_KEY` → `BuildKonfig.OPENROUTER_API_KEY`

    Only the chosen provider's key needs to be set. Read-only commands
    (`-sessions`, `-clean`) work without any key at all.
  - Mode-selecting flags (mutually exclusive):
    - `-oneshot` → single prompt → response → exit. Does **not** load or
      save history; no session is created. Best for quick questions you
      don't want polluting persisted history.
    - `-sessions` → list saved sessions and exit. Ignores every other flag.
    - `-clean` → delete every row from the history DB across all sessions
      and exit. Ignores every other flag.
    - (none of the above) → default Chat mode: REPL with persisted history.
  - Persistence flags (Chat mode only):
    - `-session <name>` → name of the conversation to resume / create.
      Valid characters: `[a-zA-Z0-9_-]`, up to 64 chars. Without it, a
      fresh random 8-hex-char id is generated and announced on stderr so
      you can come back to it later. Rejected when combined with `-oneshot`.
  - Generation knobs (persist across REPL iterations — only the prompt
    changes between turns):
    - `-provider <gemini|openrouter>` → picks which API to call. Default:
      `gemini`. Each provider has its own typed model catalog and key.
    - `-model <model-id>` → picks the model. Default depends on the
      provider — see catalogs below. Unknown ids fall through to a `Custom`
      variant and are forwarded to the provider verbatim (the provider's
      API decides whether to accept them).
    - `-maxTokens <int>` → upper bound on output tokens (Gemini:
      `generationConfig.maxOutputTokens`; OpenRouter: `max_tokens`).
    - `-stopSequence <words>` → split on whitespace; each word becomes its
      own stop sequence. Generation halts the moment *any one* of them
      appears in the output. Capped at 5 words (Gemini's limit; OpenRouter
      tolerates up to 4 — over-long lists may be rejected by OpenRouter's
      API at runtime).
    - `-endSequence <text>` → asks the model to end its response with that
      string. Lowered to a system instruction internally (Gemini's
      `systemInstruction`, OpenRouter's `system`-role message). Best-effort
      — it's a model directive, not an API guarantee.
    - `-temperature <number>` → decimal. Higher values produce more
      random / creative output, lower values are more deterministic.
      Gemini accepts roughly `0.0..2.0`. Out-of-range values are rejected
      by the API.

  Model catalogs (text generation), as of June 2026. The CLI ships a typed
  enum of these ids — any unknown id you pass is wrapped in a `Custom`
  variant and forwarded to the API as-is.

  **Gemini** ([Google's catalog](https://ai.google.dev/gemini-api/docs/models),
  default: `gemini-2.5-flash`):
  - **2.5 (GA):** `gemini-2.5-pro`, `gemini-2.5-flash`,
    `gemini-2.5-flash-lite`
  - **3.1 (mixed):** `gemini-3.1-pro-preview`, `gemini-3-flash-preview`
    (heads-up: the 3.1 Flash id literally omits the `.1` — that's how
    Google ships it), `gemini-3.1-flash-lite` (GA)
  - **3.5:** `gemini-3.5-flash` only — no Pro, no Flash-Lite tier in 3.5

  **OpenRouter** ([free-tier roster](https://openrouter.ai/models?max_price=0),
  default: `openrouter/auto:free`). The free roster changes
  often — the typed catalog ships a small handful of stable free ids and
  the meta-router; anything else can be passed as a raw id:
  - `openrouter/auto:free` (meta-router, picks an available free model
    at request time)
  - `deepseek/deepseek-r1:free`
  - `meta-llama/llama-4-maverick:free`
  - `google/gemma-3-27b-it:free`
  - `qwen/qwen3-235b-a22b:free`

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :shared:testAndroidHostTest`
- Desktop tests: `./gradlew :shared:jvmTest`
- iOS tests: `./gradlew :shared:iosSimulatorArm64Test`

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…