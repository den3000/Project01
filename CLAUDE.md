# Project01 — проектная память

KMP-проект (пакет `ru.den.writes.code.project01`).
Рабочая лошадка — **`cliJvmApp`**: JVM-консольный клиент к LLM (Gemini + OpenRouter).
**Отвечать по-русски.**

> **Этот файл — постоянная память проекта. Правится из любого worktree.**
> Чтобы параллельные worktree почти не давали merge-конфликтов (а редкие —
> были локальными и тривиальными):
> - Один факт = один короткий **независимый** пункт-строка, не сплошной текст.
> - Новое — **добавлять** отдельным пунктом; не переписывать и не
>   реформатировать соседние строки (именно это плодит конфликты).
> - `.gitattributes` помечает файл `merge=union` — git склеивает параллельные
>   добавления из разных веток автоматически, без конфликт-маркеров.
> - **Не дублировать то, что уже есть в коде** (цены, сигнатуры, версии) —
>   указатель на файл-источник.

## Карта модулей
**`shared` (commonMain, пакет `ru.den.writes.code.project01.shared.*`)** — портируемое ядро для всех таргетов (jvm/android/ios):
- `llm/` — `LlmApi` (нейтральный интерфейс + `Message`/`Role`/`GenerationParams`/`Usage`/`LlmResult`), `ModelProvider` (sealed-дискриминатор провайдера).
- `llm/gemini|openrouter|huggingface/` — по провайдеру: `*Api` (реализация `LlmApi`) + `*Dto` + `*Model` (typed каталог + `Custom`). `HttpClient` инжектится снаружи; ktor-движок (`Java`) — в cliJvmApp, не в shared.
- `context/` — `HistoryCompressor` (rolling-summary алгоритм, чистый, без `HistoryStore`) + `evenDown` (`EvenDown.kt`).
- `pricing/` — `ModelPricing`/`PricingRegistry`: цены/окна. **Single source of truth по ценам.**
- `memory/` — `Profile.kt` (`ProfileSection` style/format/constraints/context + `ProfileData` + `parseProfileData`/`renderProfileData`/`parseProfileCommand`/`isValidProfileName`), `MemoryLayer` (`composePreamble`/`composeSystem`), `MemoryMode` (PREAMBLE/SYSTEM), `MemoryModels.kt` (`RuleEntry`/`TaskNotes`), `TaskState.kt` (`TaskStage` clarification→planning→execution→validation→done + `TaskStateMachine`: `allowedNext`/`canTransition`/`parseStageSignal`).
- `agent/` — `AgentConfig` (`LlmApi` + `GenerationParams` + опц. `profileName`) + `AgentResponder.respond` (один ход без сессионного состояния: wire-list `memoryLayer+baseContext+userTurn` → `LlmApi`, парс stage-сигнала через `TaskStateMachine`) + `TurnOutcome`. Портируемое ядро, которое гоняет раннер.
- `invariant/` — `InvariantChecker` (fun interface `check(reply, rules, constraints)`) + `LlmInvariantJudge` (дефолт-реализация: независимый LLM-вызов, чистый контекст, fail-open) + `InvariantJudgePrompt` (чистый prompt-builder + tolerant JSON-parser вердикта) + `InvariantVerdict`/`InvariantViolation`. Портируемое ядро judge-слоя (инварианты-как-код).
- `util/Logging.kt` — `expect/actual logWarn` для retry-логов `*Api` (jvm/android → `System.err`, ios → `println`).
- BuildKonfig (ключи API) — тоже в `shared`, см. «Версии и ключи».

**`cliJvmApp` (пакет `ru.den.writes.code.project01.cliJvm.*`, зависит от `:shared`)** — JVM-консольный клиент:
- `main.kt` — bootstrap, Room init + миграции (v1→v4), `HttpClient(Java)`, dispatch по `CliArgs`.
- `CliArgs.kt` — sealed разбор аргументов (ListSessions/Clean/Inflate/Chat/OneShot/Memory) + `USAGE`. CLI-специфика, остаётся в cliJvmApp.
- **MVI-стек диалога** (расслоено из бывшего `SessionLoop`, который удалён): `SessionViewModel.kt` держит цикл — `state: StateFlow<UiState>` (единственный писатель), `run(IntentSource)`, гидрация/resume, оркестрация ходов, feed→repl, summary; `TurnEngine.kt` — чистый движок хода (`turn(): TurnResult`, persist + FSM-переход, без I/O; делегирует в `shared.agent.AgentResponder`); `CommandRunner.kt` — `/`-команды → строки-нотисы; `IntentSource.kt` (`PromptSourceIntents` над stdin/feed + `ChannelIntentSource` для TUI; троттл feed живёт здесь); `UiState.kt` (`UiState`/`UiIntent`/`UiEffect`/`AgentRef` + `UiLine` — расщеплён на User/Assistant/Turn/Judge/Stage/Error/Notice, каждый несёт свои данные рендера).
- **Виды — два параллельных sealed-интерфейса** (форма песочницы `cliTui/tuiViews`), по data-class-варианту на `UiLine`, каждый рендерит себя (форматирование инлайн, без общих хелперов): `plain/` — `PlainView` + `{User,Assistant,Turn,Judge,Stage,Error,Notice}PlainView` с чистыми `stdout()`/`stderr()`, драйвер `PlainRenderer` (байт-в-байт reply+footer→stdout; `[session]`/`[task]`/`[warning]`/`[error]`/`[branch]`/`[memory]`→stderr); `tui/` — `TuiView` (default-метод `wrapWords`) + те же варианты + `SessionPanelTuiView`, драйвер `TuiRenderer` (Kotter+Mordant, лента через `aside`, живая `section` = busy + панель + ввод, `toIntent`). Драйвер маппит `UiLine`→вид (`toPlainView`/`toTuiView`) и зовёт `render`. `parseSlashCommand` (`PromptSource.kt`) — общий классификатор `/`-команд для stdin-REPL и TUI.
- `ContextStrategy.kt` (full/window/summary, завязан на `HistoryStore`) + `StickyFacts.kt` (`FactsExtractor` + sticky-facts стратегия). `Summary` оборачивает shared-`HistoryCompressor`.
- `SessionStats.kt` (счётчики сессии, завязан на Room `MessageEntity`), `PromptSource.kt` (stdin/файловый ввод).
- `db/` — Room: `AppDatabase` (version=4), `MessageDao`, `HistoryStore`, entity'и (messages/summaries/facts).
- `memory/` — `MemoryStore` (markdown-файлы под `~/.project01-cli/memory/`: `profile.md`, `profiles/<name>.md`, `rules/NNN-*.md`, `tasks/<id>.md`), `MemoryProvider` (фасад: режим + taskId + activeProfileName).

**`mcpLab` (пакет `ru.den.writes.code.project01.mcpLab`, standalone, без `:shared`)** — песочница MCP на Kotlin MCP SDK (`io.modelcontextprotocol:kotlin-sdk`), два режима:
- Клиент-проба (`main.kt`/`ServerCommand.kt`/`ToolList.kt`) — спавнит любой MCP-сервер командой-аргументом, `listTools`, печатает каталог; дефолт `npx -y @modelcontextprotocol/server-everything`.
- Свой сервер `--serve` (`WeatherServer.kt`) — stdio MCP-сервер, инструмент `current_weather(city)` поверх Open-Meteo (`OpenMeteoClient.kt`, геокодинг+прогноз, без ключа). Дока — `mcpLab/README.md`.

## Тесты (offline, без сети)
- `shared/src/commonTest/…shared/` — тесты переехавшего ядра: `*ApiTest` (gemini/openrouter/huggingface), `HistoryCompressorTest`, `PricingRegistryTest`, `ProfileTest`, `MemoryLayerTest` + `FakeLlmApi`. Гонять `./gradlew :shared:jvmTest`.
- `cliJvmApp/src/test/…cliJvm/` — `TestDb` + своя копия `FakeLlmApi` + `*Test` для остающегося кода (db/`HistoryStore`/`MemoryStore`/`CliArgs`/`SessionStats`/`ContextStrategy`/`FactsExtractor`/…). MVI-стек гоняют через хелпер `runSessionForTest` (`agent/AgentTestSupport.kt`) = `TurnEngine`+`SessionViewModel`+`PlainRenderer`; байт-в-байт вывод пинит `agent/PlainViewGoldenTest`, формат per-line — `plain/*PlainViewTest`.

## Команды и verification loop
- **`./gradlew :cliJvmApp:test`** (cliJvmApp) + **`:shared:jvmTest`** (ядро) — быстрые offline-тесты.
  **Гонять после каждой правки**: `SessionViewModel`/`TurnEngine`/`PlainView`/`CliArgs`/`HistoryStore`/`MemoryStore`/`ContextStrategy` → `:cliJvmApp:test`;
  `*Api`/`HistoryCompressor`/`PricingRegistry`/`Profile`/`MemoryLayer` → `:shared:jvmTest`.
- `./gradlew :cliJvmApp:installDist` → `./cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp …`.
  **ОБЯЗАТЕЛЬНО пересобирать после правки CLI-флагов** — иначе старый бинарь не знает новых
  флагов (симптом: новый флаг «прилипает» к значению предыдущего).
- TUI-прогон: `… -prompt "…" -tui -session NAME` в **настоящем терминале** (Kotter raw-ввод не поднимается из IDE/`gradlew run`; жжёт токены — спрашивать перед запуском).
- Инспекция БД: `sqlite3 ~/.project01-cli/history.db ".schema"`.
- Real-network smoke жжёт токены — **спрашивать пользователя перед запуском**.
- MCP function-calling demo: `:mcpLab:installDist` + `:cliJvmApp:installDist`, затем `cliJvmApp -prompt "Погода в Москве?" -mcpServer "$(pwd)/mcpLab/build/install/mcpLab/bin/mcpLab --serve"`. Дёргает Gemini (2 вызова/ход) — спрашивать перед прогоном.
- `:mcpLab:test` — offline-тесты mcpLab (парсинг команды, формат погоды); живой MCP сервер/клиент — прогоном бинаря.
- **TUI из Bash проверяемо через pty** — `python pty.fork` поднимает псевдотерминал (`System.console()≠null` → TUI активен под Bash): снять вывод N секунд, kill. Контроль: обычный ход без `-mcpServer` под тем же стендом завершается.

## Грабли (не повторять)
- **Ktor engine — `Java`, не CIO** (CIO рвёт длинные thinking-ответы Gemini).
- Gemini **stateless** — историю шлём целиком каждый ход (растёт линейно).
- **Thinking-токены биллятся как output** — основная статья расхода у сильных моделей.
- Имена Gemini: `gemini-3-flash-preview` = 3.1 Flash (без `.1`); `gemini-3.5-pro` и `-3.5-flash-lite` не существуют.
- **OpenRouter free-roster протухает быстро** — `:free` id мрут (404). Сверять с
  `https://openrouter.ai/api/v1/models` (`jq`, ключ не нужен). Каталог — `OpenRouterModel.kt`,
  цены — `ModelPricing.kt`. Дефолт `openrouter/auto` — роутер, НЕ `:free` → может быть платным.
- **HF Router маршрутизирует между провайдерами** (Cerebras/Together/Fireworks/…), реальный
  тариф per-provider — цены в `ModelPricing.kt` для HF-моделей это *приближения*. Free tier
  HF — $0.10/мес кредитов поверх. 503 при cold start серверлес-модели → одна retry с
  `Retry-After` (`HuggingFaceApi`). Живой каталог: `https://router.huggingface.co/v1/models`.
- zsh-глоббинг (`?` `*` `[` `!`) в `-prompt` ломается без кавычек — оборачивать.
- **Не печатать секреты** (значения ключей из `local.properties`/`BuildKonfig`) в транскрипт.
- `readlnOrNull()` глобально кэширует `System.in` — ввод идёт через инжектируемый `PromptSource` (stdin/feed), а VM — через `IntentSource`-адаптер над ним.
- flash-lite TPM 4M — главный боттлнек нагрузочных прогонов (упираешься в rate-limit раньше, чем в переполнение контекста).
- **`Role.SYSTEM` НЕ персистится в `HistoryStore`** — memory-слой инжектится только в wire-list `TurnEngine.turn()` поверх `baseContext`. В `messages`-таблице всегда лежат только `USER`/`ASSISTANT`. Каждый провайдер сам собирает все `Role.SYSTEM` входа в нативный system-блок (Gemini → `SystemInstruction`, OpenAI-shape → один `role="system"` в начале списка); `endSequence` приклеивается к этому же блоку.
- **`-memory-mode <preamble|system>` — переключатель режима** memory-слоёв; без него memory-провайдер не создаётся и wire-list байт-в-байт совпадает с режимом без памяти. `-task <id>` без `-memory-mode` парсер отвергает.
- **Структурированный профиль** — `-memory profile <section> <text>` (section ∈ style|format|constraints|context) добавляет bullet, `<section> clear` чистит секцию, `clear` сносит весь unnamed профиль; legacy `-memory profile <free text>` всё ещё работает (если первый токен не keyword — пишет blob в `profile.md`). Аналогично REPL: `/profile <section> <text>` / `/profile clear`. `ProfileData(freeText="X")` рендерится в wire байт-в-байт как `[Profile]\nX` (legacy free-text режим).
- **Именованные профили** — `profiles/<name>.md` рядом с unnamed `profile.md`. CLI: `-profile <name>` (требует `-memory-mode`) выбирает стартовый активный; `-memory profile <name>` touch-создаёт; `-memory profile <name> <section> <text>` / `<section> clear` / `clear` редактируют именованный; `-memory profile-list` / `profile-show <name>` — просмотр. REPL: `/profile-use <name>` переключает активный, `/profile-list`, `/profile-show <name>`, `/profile <name> <section> <text>` / `/profile <name> clear`. Активный профиль выбирается **поверх** unnamed (fallback на `profile.md` если активного нет) — пользователи с unnamed профилем не сломаны.
- **Task state machine** — `TaskNotes.stage: TaskStage?` — автомат `clarification→planning→execution→validation→done` (`shared.memory.TaskState`: `TaskStateMachine.allowedNext`/`canTransition`, forward + один шаг назад, `done` терминальна). Переход **авто**: модель ставит в ответе маркер `[[stage:<next>]]`, `AgentResponder` (shared) парсит (`parseStageSignal`), а `TurnEngine.turn` валидирует по таблице и применяет (исход → `StageAdvance`, который рендерит `PlainView`/`TuiView`) — нелегальный/отсутствующий игнорится (+`[task]`-строка в stderr). Пауза — ортогональный флаг `TaskNotes.paused` (REPL `/task-pause`/`/task-resume`, CLI `-memory task <id> pause|resume`); на паузе авто-переход подавлён. Новая задача (`/task`, `-memory task <id>`) стартует на `clarification`. FSM-грани рендерятся в блок `[Current Task]` (`Stage`/`Status`/`Expected action`/`Allowed next` + протокол маркера) — `Allowed next`+протокол только когда не на паузе и не терминал.
- **Per-stage агенты** — `-stageAgent <from..to>=<provider>:<model>[@<profile>]` (повторяемый, требует `-memory-mode`): на разные стадии FSM-задачи свои модель+профиль; ход уходит агенту, чей диапазон покрывает текущую стадию задачи (иначе fallback из `-provider/-model/-profile`). Профиль агента — именованный (`@<name>`), создаётся заранее (`-memory profile <name> <section> <text>`). `StageAgentSpec`/`RoutedAgent`, маршрут — `TurnEngine.agentFor(stage)`. В мульти-агентном режиме (`routedAgents` непуст) `PlainView`/`TuiView` печатает перед ответом тег `[[AGENT: <profile>:<model>]]` (stdout/лента, не персистится; `default` если профиль не задан); в одно-агентном — нет (паритет вывода).
- **`-tui` — opt-in TUI, gated на TTY** (`CliArgs.Chat.tui`, дефолт false, reject с `-oneshot`). Вид выбирает чистая `pickView(chat.tui, System.console() != null)` в `main`: TUI только при флаге **и** настоящем TTY; feed/oneshot/non-TTY (пайп, IDE, CI) → всегда `PlainView`. Живой TUI требует настоящий терминал (из IDE/`gradlew run` raw-ввод не поднимается).
- **Kotter+Mordant склейка** — Mordant рендерит панель в строку с `AnsiLevel.NONE` (чистый box-drawing, без своих escape), цвет накладывает Kotter снаружи; Kotter — единственный владелец экрана. Авторитет ширины — `MainRenderScope.width` (Mordant `Terminal().size` врёт в raw). Лента — через Kotter `aside` (печатается раз, не мерцает); живая `section` держит только нижний блок (busy + stats-панель + ввод).
- **TUI input — два коллектора**: `onKeyPressed`/`onInputEntered` срабатывают конкурентно на одну клавишу; единственный писатель состояния (VM, через `ChannelIntentSource.offer`) делает это безопасным. Enter — только через `onInputEntered`.
- **TUI рендерит ленту колонками** (`you`/`assistant`/`turn N │ …`): user-ввод эхо-ится отдельной `UiLine.User` — в raw-режиме терминал ввод сам НЕ эхо-ит, а `PlainView` эту строку **пропускает** (там терминал уже показал набранное; повтор был бы дублем → golden остаётся байт-в-байт). `wrapWords` уважает `\n` в ответе (markdown-списки) и переносит по словам под фикс-ширину (cap 120); продолжения повторяют `│` под префиксом.
- **Рендер расщеплён по строкам и видам** — `SessionViewModel` раскладывает `TurnResult.Ok` на семантические `UiLine` (Assistant/Turn/Judge/Stage); `TuiView`/`PlainView` — sealed-интерфейсы, вид-на-вариант, форматируют сами (общий `Footer` удалён — дублирование Tui↔Plain намеренное). Драйверы `TuiRenderer`/`PlainRenderer` тонкие (маппинг строка→вид). `wrapWords` — default-метод `TuiView` (юнит-тест через инстанс варианта). Stage-строка (`[task]`) теперь рисуется и в TUI-ленте (раньше опускалась).
- **Троттл feed (16 s)** живёт на `PromptSourceIntents` (feed-источник интентов), не в `TurnEngine`; stdin/TUI → 0.
- **Per-stage judge (инварианты)** — `-judgeAgent <from..to>=<provider>:<model>` (повторяемый, БЕЗ `@profile` — судье персона не нужна; требует ≥1 `-stageAgent`): независимый кодовый enforcement rules-инвариантов поверх их инжекта в промпт. После ответа judge, чей диапазон покрывает текущую стадию задачи, отдельным LLM-вызовом (чистый контекст, без истории) судит ответ против `rules` + `constraints` профиля ОТВЕТИВШЕГО агента и ловит конфликт constraints↔rules (тоже как нарушение). При нарушении: ответ показывается, но ход НЕ сохраняется в историю и стадия держится (`TurnEngine` передаёт null в FSM); вердикт едет снимком в `TurnResult`, баннер `[invariant] …` рендерят виды (`PlainView` → stderr, `TuiView` → красным в ленте). Чистый вердикт / стадия вне покрытия judge / judge выключен → паритет. `StageJudgeSpec`/`RoutedJudge`, маршрут — `TurnEngine.judgeFor(stage)`+`maybeJudge`. Fail-open: ошибка judge-вызова → CLEAN (не блокирует ход). Параметры judge фиксированы (temperature 0, малый maxTokens), не наследуют пользовательские knobs.
  - **LLM-judge на thinking-модели режет вердикт** — у Gemini thinking-токены берутся из того же `maxTokens`; при нахождении нарушения судья думает больше → JSON-вердикт обрывается на полуслове → не парсится → fail-open молча пропускает (нарушения теряются ровно тогда, когда они есть). Лечится: judge гоняется с `thinkingBudget=0` (knob `GenerationParams.thinkingBudget`, Gemini-only → `generationConfig.thinkingConfig`; другие провайдеры игнорят) + `JUDGE_MAX_TOKENS=1024` — весь бюджет уходит в JSON. Судье thinking не нужен (структурная проверка) и вреден. Диагностика: `LlmInvariantJudge` логирует raw-вердикт в stderr + warn `verdict was not JSON → fail-open` (вскрывает маскировку).
- **MCP function-calling (боевой агент)** — `-mcpServer "<команда>"` (Chat-only) поднимает MCP-сервер подпроцессом (`cliJvmApp/McpToolClient.kt`, реализует `shared.llm.ToolExecutor`): на старте `listTools`→`ToolDefinition` (схема инструмента → JSON-Schema в `Tool.toToolDefinition`/`ToolSchema.toJsonSchema`), уезжает в `GenerationParams.tools` + executor в `AgentConfig.toolExecutor`. Нейтральные tool-типы — `shared/llm/Tool.kt` (`ToolDefinition`/`ToolCall`/`fun interface ToolExecutor`). Луп в `shared.agent.AgentResponder.respond` (≤`MAX_TOOL_ROUNDS`=4): ответ с `toolCalls` → `executor.execute` → дописать в wire `Message(ASSISTANT,"",toolCalls=…)` + `Message(USER, out, toolResultFor=name)` → послать снова → финальный текст. `GeminiApi`: `params.tools`→`functionDeclarations`; ответ→`extractToolCalls`; спец-ходы→`functionCall`/`functionResponse` (`toContentOrNull`, `response={result:…}`). Tool-обмен эфемерный — в историю только USER+финальный ASSISTANT (как `Role.SYSTEM`). FC только Gemini. Без флага wire байт-в-байт прежний (golden цел).
- **MCP-клиент на `Dispatchers.IO`** (`McpToolClient` connect/listTools/callTool) — фоновый ридер stdio-транспорта иначе на главной корутине, которую Kotter (TUI) блокирует циклом отрисовки → `callTool` не получает ответ → ход виснет на «… thinking», `/exit` не доходит (цикл интентов застрял в зависшем ходе). На IO ридер на своём потоке. plain работал и без этого (главный поток не блокирован).
- **plain-рендер: коллектор ленты на `Dispatchers.Unconfined`** (`PlainRenderer`) — фоновый коллектор иначе обгонял синхронную печать `>`/баннера из `StdinPromptSource`, и вывод хода печатался ПОСЛЕ промпта (гонка межпоточная, `yield()` не лечит). Unconfined → `flush` синхронно внутри `state.update` на потоке VM, до промпта. `TuiRenderer` не задет.
- **MCP server (`mcpLab/WeatherServer.kt`)** — `Server.createSession(transport)` + `session.onClose{ done.complete() }` + `done.join()` держит сервер живым до отключения клиента; `addTool`-handler это extension-лямбда `ClientConnection.(CallToolRequest)` — ОДИН параметр + receiver, не два; 2-арг `StdioServerTransport(in,out)` deprecated → форма с trailing-лямбдой. stderr сервера — диагностика (`redirectError(INHERIT)` в `McpToolClient`), stdout — JSON-RPC.
- **`*Api` не печатают `>>>>`-debug в stdout** (убрано из Gemini/OpenRouter/HuggingFace) — прямой `println` из shared рушил Kotter в TUI (владелец stdout) и порядок в plain. Tool-обмен хода рендерится через `UiLine.MCPLine`: `executedToolCalls` едет `TurnOutcome`→`TurnResult.Ok`→`SessionViewModel.toLines()`; `McpTuiView` (колонка `mcp │`) / `McpPlainView` (строки `[tool]/→/model/prompt`), текст — `mcpToolLines` в `UiState.kt`. Без инструментов не эмитится → golden цел.

## Стиль работы пользователя
- По-русски. **Делать только то, что попросили** — без попутных «улучшений»
  (особенно BuildKonfig в `:shared`, который он настроил сам).
- **НИКОГДА не ссылаться на «день N», «Day-N», «pre-Day-N», «учебный» и название
  челленджа** в коде, комментариях, docstring'ах, именах тестов, README, CLAUDE.md
  и любых других файлах проекта. Описывать фичу через её роль (`structured
  profile`, `named profiles`, `sliding window strategy`), а не через этап, на
  котором она добавлена. При правке кода — попутно убирать такие метки, если
  они встречаются рядом.
- Любит sealed interfaces, DI через конструктор, чистые тестируемые функции, точные комментарии/доки.
- Не любит speculative refactors / premature abstractions; за хорошо мотивированный рефакторинг — да.
- Plan-mode для крупных задач: план → разногласия → правки → одобрение → реализация. Диффы читает внимательно.
- **План — по атомарным коммитам**: каждый коммит независимо собирается и проходит тесты (green-to-green), тесты в том же коммите, что и код; порядок аддитивное → переключение → удаление. Методичка и формат — skill `atomic-plan`, применять при любом планировании (в т.ч. native plan mode).
- **Правит файлы и коммитит между ходами** — ВСЕГДА перечитывать файл перед `Edit`,
  проверять `git log`/`git status` (бывает чистое дерево там, где ждёшь правки).
- «Выведи текстом» → не создавать файлы. «Код не трогай» → менять только комментарии/доки.
- Бережёт API-токены.

## Версии и ключи
- Точные версии — `gradle/libs.versions.toml` (Kotlin 2.4.0, Ktor 3.0.3 engine Java, Room, KSP, buildkonfig…).
- Ключи: `BuildKonfig.GEMINI_API_KEY` / `OPENROUTER_API_KEY` (плагин `buildkonfig`, lowercase k, в `:shared`).
  Источник — `local.properties` (gitignored), при отсутствии ключа fallback на env-переменную того же имени
  (`shared/build.gradle.kts` → `getStringPropertyOrEnvVar`).
- Третий ключ — `BuildKonfig.HUGGINGFACE_API_KEY` (источник `HUGGINGFACE_API_KEY` в `local.properties`/env), для `-provider huggingface`.

## Worktree (Android Studio)
- Работаю в том worktree, что открыт в AS, и запускаюсь **из него**. Не из
  вложенного `.claude/worktrees/` — он ломает индекс AS.
- Память (этот файл + авто-память Claude) шарится между всеми worktree по git-репо —
  контекст не теряется при переезде.
- Run-конфиги AS — в `.run/` (Store as project file): в git, поэтому шарятся между
  worktree как и этот файл.
- Обустройство окружения (worktree-соседи, симлинк `local.properties`, скрипт
  `new-worktree.sh`, каталог `fixtures/` для больших тестовых файлов) — в
  `~/Documents/AiAdvenChallenge/README.md`.
