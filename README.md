This is a Kotlin Multiplatform project targeting Android, iOS, Desktop (JVM).

* [/iosApp](./iosApp/iosApp) contains an iOS application. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/shared](./shared/src) is for code shared across all targets (Android, iOS, Desktop/JVM) — both the
  Compose Multiplatform UI and the provider-neutral **LLM domain core** the CLI is built on: `llm/`
  (the `LlmApi` contract, provider catalogs, and the Gemini/OpenRouter/Hugging Face clients), `context/`
  (rolling-summary compaction), `pricing/`, and `memory/` (profile + rules + task model).
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
- CLI JVM app — sends a prompt to a chat-style LLM (Gemini, OpenRouter or Hugging Face),
  prints the response with a stats footer that reports both the current
  turn's token usage and the session's running totals + USD cost (the
  same SQLite row store that holds history holds the per-call token
  counts and model id, so lifetime totals survive restart). Format:
  `turn:    prompt=X output=Y thoughts=Z total=T  cost=$N`
  `context: P / W (F%)`  (only when the chosen model's context window is known)
  `session: turns=K prompt=… output=… total=…   cost=$M`
  Cost is recomputed each time from tokens + per-row `model_id` via a
  static rate table; models not in the table show `cost=$? (no pricing)`
  in place of the number. `thoughts` is reported for Gemini
  thinking-capable models and for Hugging Face reasoning models that
  surface `completion_tokens_details.reasoning_tokens` (DeepSeek-R1,
  Qwen3-Thinking, gpt-oss variants); OpenRouter rolls reasoning tokens
  into `output`. Once the prompt passes 90% of the window, a
  `[warning] context window …% full` line is printed to stderr.
  Unless `-oneshot` is passed, the agent drops into a REPL where each
  new line becomes the next prompt — or in **feed mode** (see flags
  below) reads successive chunks from a file in place of stdin. By
  default the full conversation is sent on every turn (the chat APIs are
  stateless), so multi-turn context is preserved; pick a `-strategy`
  other than `full` to bound that growth (see *Context-management* below).
  Successful turns are persisted to a local SQLite file at
  `~/.project01-cli/history.db` (single file; rows are discriminated by a
  `session_id` + `branch_id` pair), so closing and reopening the app
  picks up where you left off. Recognised REPL commands:
  - `/quit` or `/exit` (or Ctrl-D) — leave the REPL.
  - `/reuse` — feed the model's last reply back as the next prompt without
    retyping it. Handy for chain-of-thought follow-ups where you want the
    model to keep building on what it just said.
  - **Branching** (persisted per session, survives restart):
    - `/checkpoint` — print the current branch + message count as a marker.
    - `/branch <name>` — fork a new branch off the current one, copying its
      history (plus any rolling summary / sticky facts). Doesn't switch to it.
    - `/switch <name>` — move the conversation onto another branch and
      re-hydrate the strategy for it.
    - `/branches` — list the session's branches (`*` marks the current).

    Each branch is an independent conversation under the same session id:
    explore a tangent on a branch, then `/switch main` back without losing
    either thread.
  - Run: `./gradlew :cliJvmApp:run --args="-prompt <text> [...flags]"`
  - Build a packaged launcher: `./gradlew :cliJvmApp:installDist`,
    then run `./cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp -prompt "<text>"`
  - API keys are **not** CLI arguments. They are read at build time from
    `local.properties` (gitignored) or from same-named environment
    variables, and exposed via `BuildKonfig`:
    - `GEMINI_API_KEY` → `BuildKonfig.GEMINI_API_KEY`
    - `OPENROUTER_API_KEY` → `BuildKonfig.OPENROUTER_API_KEY`
    - `HUGGINGFACE_API_KEY` → `BuildKonfig.HUGGINGFACE_API_KEY`

    Only the chosen provider's key needs to be set. Read-only commands
    (`-sessions`, `-clean`) work without any key at all.
  - Mode-selecting flags (mutually exclusive):
    - `-oneshot` → single prompt → response → exit. Does **not** load or
      save history; no session is created. Best for quick questions you
      don't want polluting persisted history.
    - `-sessions` → list saved sessions and exit. One line per
      (session, branch): `<id>  <N> messages  total_tokens=<X>  cost=$<Y>`,
      with the label shown as `<id>/<branch>` for any branch other than
      `main`. A `compressed(covered=…/…, overhead=…)` segment is appended
      when the branch carries a rolling summary, and a `facts(overhead=…)`
      segment when it has a sticky-facts blob — each reporting the
      cumulative token/cost that strategy spent. Ignores every other flag.
    - `-clean` → delete every row from the history DB across all sessions
      — messages, rolling summaries and sticky facts — and exit. Ignores
      every other flag.
    - `-inflate <N> -session <name>` → duplicate the last `N` message rows
      of `<name>` back into the same session, then exit. No LLM call, no
      network, no token spend — a pure DB op used to fast-forward a
      session's context-window fill for stress-testing the overflow path
      without paying for rate-limited turns. Copies carry text + role only
      (token columns cleared, so session totals aren't double-counted).
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
    - `-byLine` → split the feed file by **lines** (one non-blank line =
      one turn) instead of fixed-size character chunks. Only valid
      alongside `-feedFile`; mutually exclusive with `-chunkChars`.
    - `-feedInstruction <text>` → prefix prepended to every chunk before
      sending (e.g. `"Briefly comment on the following text section:"`).
      Default empty — chunks are sent verbatim. Only valid alongside
      `-feedFile`.
  - Context-management flags (Chat mode only — rejected with `-oneshot`,
    which has no history). They choose how the stored history is shaped
    into each request as the conversation grows; the default `full`
    reproduces the old "send everything" behaviour exactly.
    - `-strategy <full|window|facts|summary>` → pick the strategy:
      - `full` — send the entire conversation verbatim every turn
        (default; prompt tokens grow linearly with the conversation).
      - `window` — sliding window: send only the last `-keepLast` messages
        and drop everything older. The cheapest bound on prompt growth, at
        the cost of forgetting old turns outright. No extra LLM call.
      - `facts` — sliding window **plus** a sticky memory: after each user
        turn a separate LLM call folds durable details (goals, constraints,
        names, numbers, decisions) into a small JSON blob that keeps riding
        along even after those turns scroll out of the window.
      - `summary` — rolling summary: old turns are folded into one running
        summary (a separate LLM call) so the request becomes
        `[summary] + recent tail` instead of the full history.
    - `-compress` → shorthand for `-strategy summary`.
    - `-keepLast <int>` → trailing messages kept verbatim under `window` /
      `facts` / `summary`. Default `6` (snapped down to even). Ignored
      under `full`.
    - `-summarizeEvery <int>` → `summary` only: fold once at least this
      many messages pile up beyond the kept tail. Default `10`, minimum 2.

    The `facts` and `summary` strategies persist their state per
    (session, branch), so a resumed or switched session keeps folding
    where it left off. Their extra LLM calls are billed as **overhead**,
    surfaced separately in `-sessions`. Watch the footer's `context:` line
    relative to a `full` run to see a strategy holding prompt growth down.
  - Memory layer (Chat mode only) — a persistent profile + rules + task that
    ride along in every request, stored on disk under `~/.project01-cli/memory/`
    and **never written into the conversation history** (injected per turn, not
    saved as messages). Off by default. Manage the files offline with the
    standalone `-memory` op; turn injection on for a chat with `-memory-mode`.
    - `-memory-mode <preamble|system>` → enable the layer. `preamble` injects
      it as one USER/ASSISTANT framing pair (any provider); `system` emits
      `Role.SYSTEM` messages the provider lifts into its native system slot.
      Without it the wire is byte-identical to a no-memory run; `-task` /
      `-profile` / `-stageAgent` all require it.
    - `-profile <name>` → start with this named profile active (swap mid-session
      with `/profile-use <name>`).
    - `-task <id>` → activate a working-memory task (create it first with
      `-memory task <id>`); the task carries an FSM stage.
    - `-memory <sub>` → standalone, no-LLM management (its own mode, not a
      chat): `show`; `profile [<section> <text> | <section> clear | clear]` for
      the unnamed `profile.md`; `profile <name> […]` for `profiles/<name>.md`;
      `profile-list` / `profile-show <name>`; `rule add <text>` / `rule rm <id>`;
      `task <id> [pause | resume]`. Profile sections: `style`, `format`,
      `constraints`, `context`. REPL equivalents: `/memory`, `/profile …`,
      `/profile-use`, `/rule`, `/task`, `/task-note`, `/task-pause`,
      `/task-resume`, `/memory-mode`.

    Three kinds of memory, by scope:
    - **Profile** — a persona's instructions (the four sections above). Unnamed
      `profile.md` is the fallback; named profiles are selectable and can be
      pinned per agent (see per-stage). A profile's `constraints` are
      persona-local: they apply only while that profile is active.
    - **Rules** — global invariants (architecture, stack, business rules): one
      flat numbered list injected on **every** turn regardless of the active
      profile, so they survive profile switches and hold across all per-stage
      agents. The home for "must never violate" constraints.
    - **Task FSM** — a task moves `clarification → planning → execution →
      validation → done` (one step back allowed; `done` is terminal). The stage
      is injected each turn; the model advances it by ending a reply with a
      `[[stage:<next>]]` marker, which the CLI validates against the transition
      table before applying. `/task-pause` holds the stage.
  - Per-stage agents (Chat mode + `-memory-mode` + an active task) — answer
    different stages of a task with different models **and** profiles.
    - `-stageAgent <from..to>=<provider>:<model>[@<profile>]` (repeatable) →
      bind an agent to an inclusive span of FSM stages. Each turn is routed to
      the agent whose span covers the task's current stage; uncovered stages
      (and any turn with no active task) use the default agent from
      `-provider`/`-model`/`-profile`. The model id keeps its `/` and `:` (the
      provider is the text before the first `:`); `@<profile>` pins a named
      profile to that agent (create it first; omit to use the session's active
      profile). Requires `-memory-mode`; rejected in non-chat modes.
    - In a multi-agent session each reply is prefixed with
      `[[AGENT: <profile>:<model>]]` (printed, never persisted; `default` when
      the agent has no pinned profile) so you can see which agent answered each
      turn. Single-agent sessions print no tag.
  - Invariant judge (Chat + per-stage agents + an active task) — a second,
    **independent** enforcement line for the `rules` invariants, beyond just
    injecting them into the prompt. After each reply the judge whose span
    covers the active stage audits it in a separate, context-free LLM call (no
    history — so it isn't pulled by the same pressure that produced a breach).
    - `-judgeAgent <from..to>=<provider>:<model>` (repeatable) → bind a judge
      to an FSM stage span. Persona-less — model + span only, no `@profile`.
      Requires at least one `-stageAgent` (and so `-memory-mode`).
    - The judge checks the reply against the global `rules` **plus** the
      `constraints` of the profile the answering agent spoke with, and also
      flags any of those constraints that contradict the rules.
    - On a violation the reply is still printed, then an `[invariant] …` banner
      names what broke — but the turn is NOT saved to history (so the breach
      doesn't poison later context) and the task stage is held. A clean verdict
      (or a stage no judge spans) leaves the turn untouched. Fail-open: a
      judge-call error degrades to "not blocked".
  - MCP tools / function calling (Chat only) — `-mcpServer "<command>"` spawns
    an MCP server as a subprocess (e.g. `mcpLab --serve`, see the `mcpLab`
    module) and offers its tools to the model. At startup the app calls the
    server's `tools/list` once and converts each tool's JSON-Schema into Gemini
    `functionDeclarations`, included on every request. When the model answers
    with a `functionCall` instead of text, the app runs it via the server's
    `tools/call`, feeds the result back as a `functionResponse`, and re-asks —
    up to a few rounds — until the model produces its final text using the data.
    The tool round-trip is ephemeral (only the final reply is persisted) and
    shows in the transcript as an `mcp │ …` column (TUI) / `[tool] …` lines
    (plain). Without the flag the wire is byte-identical to a tool-less run.
    **Gemini only** — the other providers don't model function calling. The tool
    declarations cross the neutral `LlmApi` boundary as `ToolDefinition` /
    `ToolExecutor`, so the agent core stays provider- and MCP-agnostic.
  - Generation knobs (persist across REPL iterations — only the prompt
    changes between turns):
    - `-provider <gemini|openrouter|huggingface>` → picks which API to
      call. Default: `gemini`. Each provider has its own typed model
      catalog and key.
    - `-model <model-id>` → picks the model. Default depends on the
      provider — see catalogs below. Unknown ids fall through to a `Custom`
      variant and are forwarded to the provider verbatim (the provider's
      API decides whether to accept them).
    - `-maxTokens <int>` → upper bound on output tokens (Gemini:
      `generationConfig.maxOutputTokens`; OpenRouter / Hugging Face:
      `max_tokens`).
    - `-stopSequence <words>` → split on whitespace; each word becomes its
      own stop sequence. Generation halts the moment *any one* of them
      appears in the output. Capped at 5 words (Gemini's limit; OpenRouter
      tolerates up to 4 — over-long lists may be rejected by OpenRouter's
      API at runtime).
    - `-endSequence <text>` → asks the model to end its response with that
      string. Lowered to a system instruction internally (Gemini's
      `systemInstruction`; OpenRouter / Hugging Face: a prepended
      `system`-role message). Best-effort — it's a model directive, not
      an API guarantee.
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
  default: `openrouter/auto`). The free roster rotates fast — these
  `:free` ids were verified live in June 2026; expect drift, and pass any
  current id as a raw `-model` (it falls through to `Custom`). The typed
  catalog ships the meta-router plus a handful of free models:
  - `openrouter/auto` — meta-router, picks a model at request time. Note:
    **not** a `:free` id, so it may route to (and bill for) a paid model.
  - `meta-llama/llama-3.3-70b-instruct:free` (131K ctx)
  - `google/gemma-4-31b-it:free` (262K ctx)
  - `qwen/qwen3-coder:free` (1M ctx)
  - `nvidia/nemotron-3-super-120b-a12b:free` (1M ctx)

  `google/gemma-3-27b-it` is still live but **paid** (~$0.08/$0.16 per 1M
  tokens) — it's not in the typed catalog, but the price table knows it,
  so a `-model google/gemma-3-27b-it` run still reports real cost.

  **Hugging Face Router**
  ([Inference Providers chat-completions](https://huggingface.co/docs/inference-providers/tasks/chat-completion),
  default: `meta-llama/Llama-3.3-70B-Instruct`). The Router fans out to
  backing providers (Cerebras, Together, Fireworks, DeepInfra…), so the
  exact rate billed depends on which provider answers the call — the
  price-table figures are *approximate*. The HF $0.10/month free credit
  pool applies on top of those rates. Available ids drift fast — verify
  against `https://router.huggingface.co/v1/models` when one starts
  404-ing; any current id passes through as `Custom`. Typed catalog:
  - `meta-llama/Llama-3.3-70B-Instruct` (131K ctx) — general
  - `deepseek-ai/DeepSeek-R1` (64K ctx) — reasoning
  - `Qwen/Qwen3-4B-Thinking-2507` (256K ctx) — light thinking
  - `Qwen/Qwen3.6-35B-A3B` (131K ctx) — MoE general
  - `openai/gpt-oss-120b` (131K ctx) — large general / tool calling

  **Demo recipes** (after `./gradlew :cliJvmApp:installDist`):

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
    -provider openrouter -model "meta-llama/llama-3.3-70b-instruct:free" \
    -prompt "Comment briefly on each chunk." \
    -feedFile bigfile.txt -chunkChars 5000 -session feed-or

  # Resume: lifetime totals (across launches) recover from the DB.
  ./cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp \
    -prompt "продолжай" -session feed-lite

  # Bound context growth with a strategy. Same line-by-line feed,
  # but old turns are folded into a rolling summary instead of resent in
  # full — compare the `context:` line (and `-sessions` overhead) against a
  # plain `-strategy full` run on the same input.
  ./cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp \
    -provider gemini -model gemini-2.5-flash-lite \
    -prompt "Комментируй кратко, помни ключевые факты." \
    -feedFile bigfile.txt -byLine \
    -strategy summary -keepLast 6 -summarizeEvery 6 \
    -session feed-summary

  # Per-stage agents: a different model + profile per task stage. Create the
  # task and named profiles offline first, then route stages to agents
  # (needs -memory-mode + the active task).
  ./cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp -memory task jwt
  ./cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp \
    -memory profile interviewer constraints "Ask first; no code yet."
  ./cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp \
    -memory profile coder constraints "Kotlin + Ktor only; no Spring."
  ./cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp \
    -prompt "Help me build JWT login." \
    -session jwt-demo -memory-mode system -task jwt \
    -stageAgent clarification..planning=gemini:gemini-2.5-flash@interviewer \
    -stageAgent execution..done=gemini:gemini-2.5-flash-lite@coder
  # → every reply is prefixed with [[AGENT: <profile>:<model>]].

  # Inventory: per-session message count + total tokens + cost.
  ./cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp -sessions
  ```

- MCP sandbox — **`mcpLab`** is a small experiments module on the official
  [Kotlin MCP SDK](https://github.com/modelcontextprotocol/kotlin-sdk), separate
  from the LLM CLI, with two modes: a **client probe** that connects to any MCP
  server over stdio and lists its tools (`mcpLab [<server command…>]`), and a
  **server** — `mcpLab --serve` runs a small Open-Meteo weather MCP server (tool
  `current_weather`) that the CLI drives via `-mcpServer` (above). Quick start:
  `./gradlew :mcpLab:installDist && ./mcpLab/build/install/mcpLab/bin/mcpLab`.
  Full docs: [mcpLab/README.md](./mcpLab/README.md).

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :shared:testAndroidHostTest`
- Desktop + shared domain-core tests (LLM API, pricing, context, memory): `./gradlew :shared:jvmTest`
- iOS tests: `./gradlew :shared:iosSimulatorArm64Test`
- CLI JVM app tests (fast, no network — all providers stubbed via
  `FakeLlmApi`): `./gradlew :cliJvmApp:test`

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…