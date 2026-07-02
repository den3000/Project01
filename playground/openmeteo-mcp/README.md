# :playground:openmeteo-mcp — MCP-сервер погоды

Standalone **MCP-сервер** (Model Context Protocol) по stdio с погодными инструментами, на
официальном **Kotlin MCP SDK** (`io.modelcontextprotocol:kotlin-sdk`). Пакет
`ru.den.writes.code.agenticHub.mcps.openmeteo`, gradle-модуль `:playground:openmeteo-mcp`. Не
зависит на доменные/LLM-модули (LLM-стек не тянется).

Спавнится MCP-клиентом (напр. `cliJvmApp -mcpServer`) подпроцессом. Флагов/режимов нет: `main()`
крутит сервер до отключения клиента (закрытия stdin). stdout — канал JSON-RPC; диагностика — в
stderr (чтобы не портить протокол).

## Инструменты
- **`current_weather`** `{ "city": string }` → однострочная сводка (место/условия/температура/ветер)
  через бесключевой Open-Meteo (`OpenMeteoClient.kt`: геокодинг → координаты → текущая погода).
- **`schedule_task`** `{ "city", "after_seconds"?|"every_seconds"? }` (ровно одно) — one-shot/периодический сбор.
- **`list_tasks`** / **`cancel_task`** `{ "id" }` — список/отмена.
- **`report`** — агрегат собранного (count/период/последнее); читает только результаты — **модель не зовёт**.

## Планировщик
Четыре scheduling-инструмента делегируют в `SchedulerEngine` из `:scheduling`. Пока клиент подключён —
фоновый тикер + периодический reporter в stderr (оба на `Dispatchers.IO`, отменяются на дисконнекте).
Состояние JSON-персистится в `~/.project01-mcplab/schedule.json` (переживает рестарт).

## Запуск
```bash
./gradlew :playground:openmeteo-mcp:installDist
BIN=./playground/openmeteo-mcp/build/install/openmeteo-mcp/bin/openmeteo-mcp
$BIN          # сервер на stdio (спавнится MCP-клиентом)
```
LLM-CLI использует его для function calling:
`cliJvmApp -mcpServer "$(pwd)/playground/openmeteo-mcp/build/install/openmeteo-mcp/bin/openmeteo-mcp"`
(см. корневой README и [`cliJvmApp/README.md`](../../agenticHubClient/apps/cliJvmApp/README.md)).
Парой с [`localfs-mcp`](../localfs-mcp/README.md) даёт кросс-серверный document-пайплайн.

## Раскладка
Всё под `src/main/kotlin/ru/den/writes/code/agenticHub/mcps/openmeteo/`: `main.kt` (`runWeatherServer()`),
`WeatherServer.kt` (регистрация инструментов + scheduler-циклы + transport), `OpenMeteoClient.kt`
(`currentWeather` + чистые `formatWeather`/`weatherCodeDescription`), `WeatherTaskHandler.kt`
(`TaskHandler`: погода по `label`=город), `SchedulingTools.kt` (чистые `scheduleFromArgs`/`renderTask`/
`renderTasks` + фабрика `buildWeatherScheduler`).

## Тесты
`./gradlew :playground:openmeteo-mcp:test` — offline: формат погоды, парс/рендер scheduling-аргументов.
Живой путь сервера — прогоном бинаря.

## Грабли
- **`addTool`-handler — extension-лямбда `ClientConnection.(CallToolRequest)`**: ОДИН параметр +
  receiver, не два. 2-арг `StdioServerTransport(in,out)` deprecated → форма с trailing-лямбдой.
- **Lifecycle:** `Server.createSession(transport)` + `onClose`-latch (`done.join()`) держит сервер
  живым до отключения клиента.
- Требования: JDK 11+; сеть к Open-Meteo (бесплатно, без ключа).
