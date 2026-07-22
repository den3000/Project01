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
export TICKTICK_ACCESS_TOKEN='...'   # OAuth2 access token (см. ниже)
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

## Раскладка

Всё под `src/main/kotlin/ru/den/writes/code/agenticHub/mcps/ticktick/`: `main.kt` (токен из env →
`runTicktickServer`), `TicktickServer.kt` (HTTP-клиент с Bearer + регистрация инструментов +
stdio-transport), `TicktickApi.kt` (порт-`interface` I/O) + `HttpTicktickApi.kt` (реальная реализация),
`TicktickReports.kt` (логика/формат — **факты, модель не зовёт**), `Dtos.kt` (wire-DTO,
`ignoreUnknownKeys`).

## Запуск

```bash
./gradlew :playground:ticktick-mcp:installDist
BIN=./playground/ticktick-mcp/build/install/ticktick-mcp/bin/ticktick-mcp
$BIN          # сервер на stdio (спавнится MCP-клиентом)
```

LLM-CLI использует его для function calling:
`cliJvmApp -mcpServer "$(pwd)/playground/ticktick-mcp/build/install/ticktick-mcp/bin/ticktick-mcp"`.

## Тесты

`./gradlew :playground:ticktick-mcp:test` — offline: логика/формат `TicktickReports` на фейке порта.
Живой путь сервера — прогоном бинаря с реальным токеном.

## Грабли

- **`addTool`-handler — extension-лямбда `ClientConnection.(CallToolRequest)`**: ОДИН параметр +
  receiver. Lifecycle — `Server.createSession(transport)` + `onClose`-latch (`done.join()`).
- **Официальный Open API отдаёт только незавершённые задачи** и не умеет ни списка выполненных, ни
  фильтра по датам, ни тегов — «что сделано» детектируется снапшотом (см. следующие инструменты).
- Требования: JDK 11+; сеть к api.ticktick.com; валидный `TICKTICK_ACCESS_TOKEN` в env.
