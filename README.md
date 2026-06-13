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
  prints the response with a stats footer that reports both the current
  turn's token usage and the session's running totals + USD cost (the
  same SQLite row store that holds history holds the per-call token
  counts and model id, so lifetime totals survive restart). Format:
  `turn:    prompt=X output=Y thoughts=Z total=T  cost=$N`
  `session: turns=K prompt=… output=… total=…   cost=$M`
  Cost is recomputed each time from tokens + per-row `model_id` via a
  static rate table; models not in the table show `cost=$? (no pricing)`
  in place of the number. `thoughts` is included only for Gemini
  thinking-capable models (OpenRouter rolls reasoning tokens into
  `output`).
  Unless `-oneshot` is passed, the agent drops into a REPL where each
  new line becomes the next prompt — or in **feed mode** (see flags
  below) reads successive chunks from a file in place of stdin. The
  full conversation is sent on every turn (the chat APIs are stateless),
  so multi-turn context is preserved. Successful turns are persisted to
  a local SQLite file at `~/.project01-cli/history.db` (single file,
  sessions live in a `session_id` column), so closing and reopening the
  app picks up where you left off. Recognised REPL commands:
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
    - `-sessions` → list saved sessions and exit. Output per session:
      `<id>  <N> messages  total_tokens=<X>  cost=$<Y>`. Ignores every
      other flag.
    - `-clean` → delete every row from the history DB across all sessions
      and exit. Ignores every other flag.
    - (none of the above) → default Chat mode: REPL with persisted history.
  - Persistence flags (Chat mode only):
    - `-session <name>` → name of the conversation to resume / create.
      Valid characters: `[a-zA-Z0-9_-]`, up to 64 chars. Without it, a
      fresh random 8-hex-char id is generated and announced on stderr so
      you can come back to it later. Rejected when combined with `-oneshot`.
  - Feed-mode flags (Chat mode only; rejected when combined with `-oneshot`):
    - `-feedFile <path>` → replaces stdin as the prompt source: reads
      the file in successive character-sized chunks and sends each one
      as the next user turn. The loop stops when the file is exhausted
      or the provider returns an error. Useful for demonstrating
      monotonic context growth — you can watch `session:` accumulate
      cost and tokens turn after turn, and eventually see the API
      reject a request once the conversation outgrows the model's
      context window.
    - `-chunkChars <int>` → chunk size in **characters** (UTF-8 safe via
      a Reader, not bytes). Default `2500`. Only valid alongside `-feedFile`.
    - `-feedInstruction <text>` → prefix prepended to every chunk before
      sending (e.g. `"Briefly comment on the following text section:"`).
      Default empty — chunks are sent verbatim. Only valid alongside
      `-feedFile`.
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

  **Day-8 token / cost demo recipes** (after `./gradlew :cliJvmApp:installDist`):

  ```bash
  # Cheap real run on Gemini Flash-Lite. Each chunk-turn appends to the
  # running session totals — watch the `session:` line grow.
  ./cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp \
    -provider gemini -model gemini-2.5-flash-lite \
    -prompt "Получишь файл по кусочкам — комментируй кратко." \
    -feedFile bigfile.txt -chunkChars 3000 \
    -session feed-lite

  # Stress test: same model (1M context window), larger chunks. With
  # a sufficiently large file the context fills up and the provider
  # eventually returns a 4xx; agent prints `[error]` and stops the
  # feed loop, then prints the final `[session-summary]`.
  ./cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp \
    -provider gemini -model gemini-2.5-flash-lite \
    -prompt "Кратко прокомментируй каждый кусок." \
    -feedFile bigfile.txt -chunkChars 30000 \
    -session feed-bust

  # Same idea against a smaller free OpenRouter window — fills up
  # faster, useful for poking at the overflow path without a big file.
  ./cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp \
    -provider openrouter -model "google/gemma-3-27b-it:free" \
    -prompt "Comment briefly on each chunk." \
    -feedFile bigfile.txt -chunkChars 5000 -session feed-or

  # Resume: lifetime totals (across launches) recover from the DB.
  ./cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp \
    -prompt "продолжай" -session feed-lite

  # Inventory: per-session message count + total tokens + cost.
  ./cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp -sessions
  ```

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :shared:testAndroidHostTest`
- Desktop tests: `./gradlew :shared:jvmTest`
- iOS tests: `./gradlew :shared:iosSimulatorArm64Test`

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…