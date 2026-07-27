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
  `AgentResponder`); флаг `stallHint` — нудж модели из застрявшей стадии (см. «Грабли»);
  `CommandRunner` — `/`-команды → нотисы.
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

Тесты движков разведены по реализациям, каждая в своём подпакете: `fsm/` — про делегирование
(машина подставная, проверяется, о чём её спросили и что движок сделал с ответом), `inline/` — про
старый `InlineFsmTurnEngine`, который пока и стоит в проде (нудж, пауза, приватный счётчик +
контракт хода). Правила задачи не проверяются ни там, ни там: они в `features:fsm`.

**`TurnEngineLiveTest`** (`jvmTest`, opt-in `-PliveTests`, **жжёт токены**, скип без `GEMINI_API_KEY`) —
стенд стабильности FSM: гоняет реальный `TurnEngine` (настоящий Gemini + Room in-memory + файловая
память во временном каталоге; без судьи/RAG/MCP — изолирует канал стадии) на `MINIMAL_TASK`/`SIMPLE_TASK`
и печатает метрики: per-turn `outcome` (ADVANCED/REJECTED/NO_MOVE/FAILED) + токены + время, per-run
сводку (`reachedDone`, advances/rejects/noMoves/failures). Ассертов на модель нет — это ЗАМЕР, им
сравнивают конфигурации (напр. `stallHint` on/off) по `reachedDone N/reps`. Каветат: у flash-lite
дневная дисперсия огромна (baseline от 11/20 до 19/20 в разные прогоны) — сравнивать конфигурации
можно только В ОДНОМ прогоне, а не с числом из прошлого.

## Грабли
- **Тихий NO_MOVE-лок FSM на слабой модели** — модель шлёт маркер ТЕКУЩЕЙ стадии (`[[stage:validation]]`
  будучи в validation) → `proposedStage == from` → `StageAdvance.None` → застой без единого сигнала.
  Корень — асимметрия фидбека: незаконный скип модель узнаёт (`pendingStageRejection`), а тихий столл
  нет. Лечится флагом `stallHint`: 2 подряд no-move хода на активной нетерминальной непаузной стадии →
  одноразовая `[fsm]`-нота, называющая СЛЕДУЮЩУЮ стадию. Дефолт off (task-less чат и тесты байт-в-байт),
  `buildSessionViewModel` включает в бою. Замер на стенде: подсказка направление даёт, но слабая модель
  её часто игнорирует — статзначимого выигрыша нет, потому она и осталась дешёвой страховкой, а не
  несущим механизмом.
- **Рестарт задачи переключает ветку истории и переживает только живую сессию** — `FsmTurnEngine` на
  `RetryOutcome.Restarted` делает `switchTo("<стартовая ветка>-attempt-N")`, чтобы новая попытка не
  читала переписку провалившейся. Имя стартовой ветки движок держит в поле, никуда не пишет, а сессия
  при запуске открывает `DEFAULT_BRANCH` (`main`): прервал задачу, перезапустил CLI (или `/resume`) —
  и продолжаешь с ветки ПРОВАЛИВШЕЙСЯ попытки, а всё после рестарта осталось там, куда никто не
  заглядывает. Сводка и sticky-facts тоже привязаны к паре (session, branch) — новая ветка стартует
  с пустых. Чинить надо на старте сессии (открывать последнюю ветку задачи, а не `main`).
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
