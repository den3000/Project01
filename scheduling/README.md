# :scheduling — переиспользуемое ядро планировщика

Лёгкое портируемое ядро для **отложенных (one-shot)** и **периодических** фоновых задач: модель
задачи, математика «когда пора», tick-движок, персист и агрегация результатов. Чистый Kotlin —
ничего не знает про MCP, MVI-цикл агента, LLM или конкретный источник данных. Всё доменное
инжектится.

## Зачем отдельный модуль
Один и тот же планировщик нужен в двух местах, которым нельзя его дублировать:
- **openmeteo-mcp** (MCP-сервер) — отдаёт scheduling-*инструменты* и собирает данные в фоне.
- **cliJvmApp** (агент) — опрашивает MCP-инструмент по расписанию и сам делает сводку.

Оба зависят на этот модуль **аддитивно** (`implementation(projects.scheduling)`). Ядро без
зависимостей на доменные/LLM-модули, чтобы openmeteo-mcp не тянул LLM-стек и оставался standalone.

## API
- **`Schedule`** — `After(delayMs)` (one-shot/напоминание) или `Every(intervalMs)` (периодика).
  Sealed — новые виды (напр. cron) добавляются без правки существующих.
- **`ScheduledTask` / `TaskStatus` (ACTIVE/DONE/CANCELLED) / `TaskResult`** — `@Serializable`
  data-модель, без поведения.
- **Математика** (чистая, clock инжектится — ядро само часы не читает): `Schedule.nextRunAt(anchor)`,
  `ScheduledTask.isDue(now)`, `ScheduledTask.advance(now)`.
- **`TaskHandler`** — `suspend (ScheduledTask) -> String?`: non-null — синхронный результат (движок
  хранит), `null` — задача отработала асинхронно (хранить нечего).
- **`ScheduleStore`** — шов персиста: `InMemoryScheduleStore` + `JsonFileScheduleStore` (атомарная
  запись temp→rename; битый/отсутствующий файл = пустое состояние).
- **`SchedulerEngine`** — `add`/`cancel`/`list`/`tick`/`runLoop(tickMs)`/`summary` под `Mutex`. НЕ
  выбирает Dispatcher и не создаёт scope — `runLoop` крутит вызывающий, в своём контексте.
- **`summarize(results)`** — count + временной диапазон + последний текст.
- **Один `TaskHandler` на движок** — задачи различаются по `label`/`id` (convention).

## Интеграции (сделаны)
- **openmeteo-mcp:** `WeatherTaskHandler` поверх `OpenMeteoClient`; MCP-инструменты
  `schedule_task`/`list_tasks`/`cancel_task`/`report` делегируют в `engine.*`; `runLoop` +
  периодическая сводка на `Dispatchers.IO`, отмена на `session.onClose`; `JsonFileScheduleStore`
  (переживает рестарт). См. [`playground/openmeteo-mcp/README.md`](../playground/openmeteo-mcp/README.md).
- **cliJvmApp:** `CliTaskHandler` роутит задачу по id — `collect` зовёт MCP-инструмент напрямую
  (`McpToolClient.execute`, без токенов), `agent` инжектит `UiIntent.Submit` в сериализованный
  MVI-цикл. Драйвится флагом `-schedule` + REPL `/schedule` (add · list · `clear [<id>]`); отчёт —
  feed-строкой, только при изменении. Per-session `InMemoryScheduleStore`. См.
  [`cliJvmApp/README.md`](../agenticHubClient/apps/cliJvmApp/README.md).

## Тесты
`./gradlew :scheduling:test` — offline (`Schedule`-математика, движок, оба стора, `summarize`).
