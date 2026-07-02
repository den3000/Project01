# :agenticHubClient:features:lifecycle:session — MVI-runtime сессии

JVM-модуль: портируемый цикл диалога (MVI), движок хода, `/`-команды, источники интентов/промптов и
glue планировщика. Собирается фабрикой `buildSessionViewModel`; апп (`cliJvmApp`) инжектит рендереры
и конкретные I/O-реализации.

## Публичный API
- `SessionViewModel` — цикл: `state: StateFlow<UiState>` (единственный писатель), `run(IntentSource)`,
  гидрация/resume, оркестрация ходов, feed→repl, summary.
- `TurnEngine` — чистый движок хода (`turn(): TurnResult`, persist + FSM-переход; делегирует в
  `AgentResponder`); `CommandRunner` — `/`-команды → нотисы.
- `SessionAssembly` (`buildSessionViewModel()` + `startSchedulerLoops()`), `SessionHydration`
  (`contextStrategy()`/`memoryProvider()`).
- `UiState`/`UiIntent`/`UiEffect`/`UiLine`(+`mcpToolLines`)/`AgentRef`/`Overlay`/`PickerKind`/
  `SessionCommand`; `TurnResult`(+`SessionStatsSnapshot`/`StageAdvance`); `IntentSource`
  (`PromptSourceIntents`/`ChannelIntentSource`/`MergedIntentSource`); `PromptSource`
  (+`PromptResult`/`commandCatalog`).
- Scheduler-glue: `SchedulerControl`/`CliTaskHandler`/`ScheduleAction` (поверх `:scheduling`).

## Зависимости
- `api(lifecycle:command)` + `api(features:memory)` + `api(features:agent)`,
  `implementation(platform:logging)` + `implementation(:scheduling)`. JVM (завязка на `:scheduling`;
  KMP-изация отложена). Потребитель — `apps:cliJvmApp`.

## Тесты
MVI-стек гоняют app-тесты в `apps:cliJvmApp:test` (`runSessionForTest` = `TurnEngine`+
`SessionViewModel`+`PlainRenderer`, golden `PlainViewGoldenTest`).

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
