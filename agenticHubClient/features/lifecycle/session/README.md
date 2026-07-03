# :agenticHubClient:features:lifecycle:session — MVI-runtime сессии

KMP-модуль (common; таргеты jvm/android/ios): портируемый цикл диалога (MVI), движок хода,
`/`-команды, источники интентов/промптов и glue планировщика. Собирается фабрикой
`buildSessionViewModel`; апп (`cliJvmApp`) инжектит рендереры и конкретные I/O-реализации. Две
платформенные примитивы (часы + диспетчер планировщика) — за expect/actual (`SessionPlatform`; jvm
реальный, ios/android `TODO()`). `CommandRunnerTest` (java.nio) в `jvmTest`, остальные — `commonTest`.

## Публичный API
- `SessionViewModel` — цикл: `state: StateFlow<UiState>` (единственный писатель), `run(IntentSource)`,
  гидрация/resume, оркестрация ходов, feed→repl, summary.
- `TurnEngine` — чистый движок хода (`turn(): TurnResult`, persist + FSM-переход; делегирует в
  `AgentResponder`); `CommandRunner` — `/`-команды → нотисы.
- `SessionAssembly` (`buildSessionViewModel()` + `startSchedulerLoops()`). Гидрация
  `SessionInitialState` (`contextStrategy`/`resolveMemoryProvider`/`resolveHistoryStore`) — в
  composition-root (cliJvmApp `commandMappers`, резолв из Koin), не здесь.
- `di/`: `sessionModule` — `factory<SessionAssembly> { (a: SessionAssemblyArgs) -> buildSessionViewModel(…) }`;
  `SessionAssemblyArgs` — data-holder на 9 аргументов (обход лимита `parametersOf` в 5). Дока — [DI.md](../../../DI.md).
- `UiState`/`UiIntent`/`UiEffect`/`UiLine`(+`mcpToolLines`)/`AgentRef`/`Overlay`/`PickerKind`/
  `SessionCommand`; `TurnResult`(+`SessionStatsSnapshot`/`StageAdvance`); `IntentSource`
  (`PromptSourceIntents`/`ChannelIntentSource`/`MergedIntentSource`); `PromptSource`
  (+`PromptResult`/`commandCatalog`).
- Scheduler-glue: `SchedulerControl`/`TaskHandlerImpl`/`ScheduleAction` (поверх `:scheduling`).

Раскладка по подпакетам: base `…session` (SessionViewModel/CommandRunner/SessionAssembly/UiState/
PromptSource), `…session.turn` (TurnEngine/TurnResult), `…session.intents` (IntentSource +
PromptSourceIntents/ChannelIntentSource/MergedIntentSource), `…session.scheduling` (SchedulerControl/
ScheduleAction/TaskHandlerImpl).

## Зависимости
- `api(lifecycle:command)` + `api(features:memory)` + `api(features:agent)`,
  `implementation(platform:logging)` + `implementation(:scheduling)` + `implementation(koin.core)` (для di).
  JVM (завязка на `:scheduling`; KMP-изация отложена). Потребитель — `apps:cliJvmApp`.

## Тесты
`./gradlew :agenticHubClient:features:lifecycle:session:test` — unit-тесты MVI/turn/intents/scheduling
(TurnResult/UiState/Overlay/CommandPalette/MergedIntentSource/TaskHandlerImpl/CommandRunner).
Интеграция (`runSessionForTest` = `TurnEngine`+`SessionViewModel`+`PlainRenderer`, golden
`PlainViewGoldenTest`) остаётся в `apps:cliJvmApp:test` (нужен `PlainRenderer`).

## Грабли
- **Троттл feed (16 s)** живёт на `PromptSourceIntents` (feed-источник), не в `TurnEngine`; stdin/TUI → 0.
- **Scheduler-инъекция в MVI (`MergedIntentSource`)** — фон инжектит ходы/feed через опц.
  `schedulerInbox`; при `inbox==null` — старый путь `source.next()` (golden байт-в-байт). Primary
  пампится через `produce`, `select` с primary-первым (user > фон).
- **Pump ОБЯЗАН break на `Exit`** — push-источник (`ChannelIntentSource`) отдаёт `Exit` (не null);
  без break pump висит на `primary.next()`, `run()` не возвращается, `/exit` в TUI не выходит.
- **Scheduler reporter постит сводку только при ИЗМЕНЕНИИ** (baseline = пустая `engine.summary()`) —
  иначе «No results yet.» лезет feed-строкой каждые 30 s.
- **`readlnOrNull()` кэширует `System.in` глобально** — ввод идёт через инжектируемый `PromptSource`
  (stdin/feed), VM — через `IntentSource`-адаптер.
- **Рендер расщеплён по строкам** — `SessionViewModel` раскладывает `TurnResult.Ok` на семантические
  `UiLine`; форматируют сами виды (в `apps:cliJvmApp`), драйверы тонкие.
