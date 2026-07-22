# :playground:ticktick-mcp — MCP-сервер TickTick

Standalone **MCP-сервер** (Model Context Protocol) по stdio над задачами/проектами
[TickTick](https://ticktick.com) через **официальный Open API** (OAuth2 Bearer), на официальном
**Kotlin MCP SDK** (`io.modelcontextprotocol:kotlin-sdk`). Пакет
`ru.den.writes.code.agenticHub.mcps.ticktick`, gradle-модуль `:playground:ticktick-mcp`. Не зависит на
доменные/LLM-модули.

Спавнится MCP-клиентом (напр. `cliJvmApp -mcpServer`) подпроцессом. Флагов нет: `main()` крутит сервер
до отключения клиента (закрытия stdin). stdout — канал JSON-RPC; диагностика — в stderr.

## Доступ (OAuth2)

Официальный Open API (`https://api.ticktick.com/open/v1`) требует Bearer-токен. Токен берётся **из
окружения** (не из argv); без него сервер печатает подсказку в stderr и выходит:

```bash
export TICKTICK_ACCESS_TOKEN='...'      # OAuth2 access token (см. ниже)
export TICKTICK_SNAPSHOT_DIR='...'      # опц.: куда писать снапшоты (дефолт ~/.project01-mcplab/ticktick)
export WEEK_TZ='Europe/Moscow'          # опц.: зона для дат диапазона (дефолт — зона сервера)
```

Токен добывается разово по OAuth2 authorization-code flow:
1. Зарегистрировать приложение в TickTick Developer Center → `client_id` / `client_secret`.
2. Открыть `https://ticktick.com/oauth/authorize?client_id=...&scope=tasks:read&state=x&redirect_uri=...&response_type=code`,
   подтвердить, забрать `code` из redirect.
3. Обменять `code` на токен: `POST https://ticktick.com/oauth/token`
   (`client_id`, `client_secret`, `code`, `grant_type=authorization_code`, `redirect_uri`) → `access_token`.

Для `list_projects` / чтения недели достаточно scope `tasks:read`.

## Инструменты

- **`list_projects`** `{}` → проекты (списки) аккаунта, `id  name` по строке. `GET /open/v1/project`.
- **`snapshot_week`** `{ "from": "YYYY-MM-DD", "to": "YYYY-MM-DD", "label"? }` → снимок плана недели:
  сохраняет на диск все незавершённые задачи с dueDate в полуоткрытом `[from, to)` (`from` включительно,
  `to` исключительно), чтобы `review_week` потом сверил «что сделано» (API не отдаёт выполненные).
  **Запускать в НАЧАЛЕ недели.** `label` (напр. `2026-W29`) именует снапшот; дефолт — `<from>_<to>`.
  Даты читаются в зоне `WEEK_TZ`.
- **`review_week`** `{ "label": "2026-W29" }` → сверка план-vs-факт: по снапшоту `label` перезапрашивает
  каждую задачу и раскладывает на **done** (`status 2`) / **not done** (`status 0`) / **gone** (404 —
  скорее всего завершена-и-заархивирована, реже удалена). **Запускать в КОНЦЕ недели** (после
  `snapshot_week` в начале). `GET /open/v1/project/{projectId}/task/{taskId}`.

## Ограничение snapshot-diff (честно)

Официальный API не отдаёт список выполненных, поэтому «сделано» вычисляется только по снапшоту:
- задачи, **созданные и закрытые внутри недели** (не попавшие в снапшот начала недели), TickTick-частью
  не видны — их закрывает вторая половина картины (реальное время из aTimeLogger);
- **`gone` (404)** не отличает удалённую задачу от завершённо-заархивированной — помечаем как «likely done»;
- нужен снимок в начале недели — задним числом неснятую неделю не разобрать.

## Раскладка

Всё под `src/main/kotlin/ru/den/writes/code/agenticHub/mcps/ticktick/`: `main.kt` (токен из env →
`runTicktickServer`), `TicktickServer.kt` (HTTP-клиент с Bearer + регистрация инструментов +
stdio-transport), `TicktickApi.kt` (порт-`interface` I/O) + `HttpTicktickApi.kt` (реальная реализация),
`TicktickReports.kt` (логика/формат — **факты, модель не зовёт** + чистые `isPlannedInRange`/
`parseTicktickInstantMillis`/`localDateToEpochMillis`), `SnapshotStore.kt` (порт персистентности
`SnapshotStore` + `FileSnapshotStore` + модели `WeekSnapshot`/`PlannedTask`), `WeekReview.kt` (чистые
`Outcome`/`classifyOutcome`/`buildWeekReview`), `Dtos.kt` (wire-DTO, `ignoreUnknownKeys`).

## Запуск

```bash
./gradlew :playground:ticktick-mcp:installDist
BIN=./playground/ticktick-mcp/build/install/ticktick-mcp/bin/ticktick-mcp
$BIN          # сервер на stdio (спавнится MCP-клиентом)
```

LLM-CLI использует его для function calling:
`cliJvmApp -mcpServer "$(pwd)/playground/ticktick-mcp/build/install/ticktick-mcp/bin/ticktick-mcp"`.

## Тесты

`./gradlew :playground:ticktick-mcp:test` — offline: логика `TicktickReports` на фейках порта и
снапшот-стора (`TicktickReportsTest`), чистые функции диапазона/дат/формата (`TicktickWeekTest`:
`isPlannedInRange`/`parseTicktickInstantMillis`/`formatSnapshot`), классификация/отчёт
(`WeekReviewTest`: `classifyOutcome`/`buildWeekReview`). Живой путь сервера — прогоном бинаря с реальным
токеном.

## Грабли

- **`addTool`-handler — extension-лямбда `ClientConnection.(CallToolRequest)`**: ОДИН параметр +
  receiver. Lifecycle — `Server.createSession(transport)` + `onClose`-latch (`done.join()`).
- **Официальный Open API отдаёт только незавершённые задачи** и не умеет ни списка выполненных, ни
  фильтра по датам, ни тегов — «что сделано» детектируется снапшотом (см. следующие инструменты).
- Требования: JDK 11+; сеть к api.ticktick.com; валидный `TICKTICK_ACCESS_TOKEN` в env.
