# :playground:atimelogger-mcp — MCP-сервер aTimeLogger

Standalone **MCP-сервер** (Model Context Protocol) по stdio над личными данными трекинга времени из
[aTimeLogger](https://app.atimelogger.com), на официальном **Kotlin MCP SDK**
(`io.modelcontextprotocol:kotlin-sdk`). Пакет `ru.den.writes.code.agenticHub.mcps.atimelogger`,
gradle-модуль `:playground:atimelogger-mcp`. Не зависит на доменные/LLM-модули.

Спавнится MCP-клиентом (напр. `cliJvmApp -mcpServer`) подпроцессом. Флагов нет: `main()` крутит сервер
до отключения клиента (закрытия stdin). stdout — канал JSON-RPC; диагностика — в stderr.

## Доступ

API aTimeLogger v2 (`https://app.atimelogger.com/api/v2`) — авторизация **HTTP Basic**. Креды берутся
**из окружения** (не из argv — argv виден в `ps`); без них сервер печатает подсказку в stderr и выходит:

```bash
export ATIMELOGGER_USERNAME='...'   # логин аккаунта aTimeLogger
export ATIMELOGGER_PASSWORD='...'   # пароль аккаунта
export WEEK_TZ='Europe/Moscow'      # опц.: зона для дат диапазона (дефолт — зона сервера)
```

Заголовок собирается один раз в `defaultRequest` клиента (`basicAuthHeader`); значение пароля никуда не
логируется.

## Инструменты

- **`list_activity_types`** `{}` → типы активностей аккаунта (имя), по строке на тип.
  `GET /types`. Нужен, чтобы знать имена категорий, против которых считается время.
- **`time_by_activity`** `{ "from": "YYYY-MM-DD", "to": "YYYY-MM-DD" }` → суммарное время по типам за диапазон
  (сортировка по времени + итог). `GET /intervals`. `from` — включительно, `to` — **исключительно** (день
  ПОСЛЕ последнего). Полуоткрытый `[from, to)`; интервалы клипаются к окну (пограничный — только его часть в
  окне). Даты читаются в зоне `WEEK_TZ`. Один запрос до 2000 интервалов (недели хватает с запасом; переполнение
  — предупреждение в stderr).

## Раскладка

Всё под `src/main/kotlin/ru/den/writes/code/agenticHub/mcps/atimelogger/`: `main.kt` (креды из env →
`runAtimeloggerServer`), `AtimeloggerServer.kt` (HTTP-клиент с Basic + регистрация инструментов +
stdio-transport), `AtimeloggerApi.kt` (порт-`interface` I/O) + `HttpAtimeloggerApi.kt` (реальная
реализация), `AtimeloggerReports.kt` (логика/формат — **факты, модель не зовёт**), `Dtos.kt`
(wire-DTO, `ignoreUnknownKeys`), `Auth.kt` (`basicAuthHeader`).

## Запуск

```bash
./gradlew :playground:atimelogger-mcp:installDist
BIN=./playground/atimelogger-mcp/build/install/atimelogger-mcp/bin/atimelogger-mcp
$BIN          # сервер на stdio (спавнится MCP-клиентом)
```

LLM-CLI использует его для function calling:
`cliJvmApp -mcpServer "$(pwd)/playground/atimelogger-mcp/build/install/atimelogger-mcp/bin/atimelogger-mcp"`.

## Тесты

`./gradlew :playground:atimelogger-mcp:test` — offline: логика `AtimeloggerReports` на фейке порта
(`AtimeloggerReportsTest`), чистые функции агрегации/формата/дат (`AtimeloggerFormatTest`:
`aggregateByActivity`/`formatDuration`/`formatTimeByActivity`/`localDateToEpochSeconds`), `basicAuthHeader`
(`AtimeloggerAuthTest`). Живой путь сервера — прогоном бинаря с реальными кредами.

## Грабли

- **`addTool`-handler — extension-лямбда `ClientConnection.(CallToolRequest)`**: ОДИН параметр +
  receiver. Lifecycle — `Server.createSession(transport)` + `onClose`-latch (`done.join()`).
- Точные имена полей ответа `/types`/`/intervals` подтверждаются по живому ответу в момент, когда поле
  реально читается; DTO defaulted + `ignoreUnknownKeys`, чтобы расхождение не роняло декод.
- Требования: JDK 11+; сеть к app.atimelogger.com; валидные креды в env.
