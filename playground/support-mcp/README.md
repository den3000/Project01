# :playground:support-mcp — support MCP-сервер (read-only, поверх JSON)

Standalone **MCP-сервер** по stdio: отдаёт ассистенту read-only-доступ к фикстуре пользователей и
тикетов поддержки. На **Kotlin MCP SDK** (`io.modelcontextprotocol:kotlin-sdk`). Пакет
`ru.den.writes.code.agenticHub.mcps.support`, gradle-модуль `:playground:support-mcp`. Не зависит на
доменные/LLM-модули.

Спавнится MCP-клиентом подпроцессом. Аргумент: **первый — путь к папке** с `users.json` и
`tickets.json` (по умолчанию — рабочая директория процесса). `main()` крутит сервер до отключения
клиента (stdin закрылся). stdout — JSON-RPC; диагностика — в stderr.

## Инструменты (все read-only)

- **`list_tickets`** `{}` — все тикеты (`id [status, priority] subject (customer USER-*)`),
  свежеобновлённые сверху.
- **`get_ticket`** `{ "id": string }` — полный тикет: subject, description, status, priority,
  customer, created/updated, комментарии. Неизвестный id → внятная пометка `(no ticket <id>)`.
- **`search_tickets`** `{ "query": string }` — тикеты, у которых query — подстрока (case-insensitive)
  subject или description. Тот же формат, что у `list_tickets`.
- **`get_user`** `{ "id": string }` — full user (name, email, tariff, product, since). Неизвестный
  id → `(no user <id>)`.

Данные читаются с диска **при каждом вызове** — фикстура маленькая, парсинг тонкий, зато файлы
можно править между тиками без рестарта сервера.

## Запуск

```bash
./gradlew :playground:support-mcp:installDist
BIN=./playground/support-mcp/build/install/support-mcp/bin/support-mcp
DATA=./demo/ctt-support
$BIN "$DATA"
```

Подключение к ассистенту (cliJvmApp): аргументы идут словами в команде сервера, т.к. `-mcpServer`
бьётся по whitespace:

```bash
cliJvmApp -mcpServer "$BIN $DATA" \
  -prompt "…" -task TICKET-4412 -rag ctt-support \
  -agent provider gemini profile support mode system
```

Готовый рецепт с профилем/правилами/RAG — в [`demo/ctt-support/`](../../demo/ctt-support/).
Инструменты обнаруживаются клиентом автоматически (`listTools`) — код клиента не меняется.

## Формат фикстуры

Ожидаемые поля (лишние — игнорируются, `ignoreUnknownKeys = true`):

```json
// users.json
[{"id":"USER-101","name":"…","email":"…","tariff":"business",
  "product":"CTT","since":"2024-06-15"}]

// tickets.json
[{"id":"TICKET-4412","subject":"…","description":"…",
  "status":"open","priority":"high",
  "createdAt":"2026-07-10T09:12:00Z","updatedAt":"2026-07-16T14:20:00Z",
  "customerId":"USER-102",
  "comments":[{"author":"USER-102","at":"2026-07-10T09:12:00Z","text":"…"}]}]
```

`status`/`priority` — строки, без enum-валидации. Смысловой словарь описан в
[demo/ctt-support/README.md](../../demo/ctt-support/README.md).

## Раскладка

Всё под `src/main/kotlin/ru/den/writes/code/agenticHub/mcps/support/`: `main.kt` (путь к данным из
args → `runSupportServer`), `SupportServer.kt` (регистрация инструментов + transport),
`SupportRepo.kt` (`Loader`-порт, `SupportRepo` — чтение/поиск и форматирование ответов,
`FileLoader` — реальный `File.readText`).

## Тесты

`./gradlew :playground:support-mcp:test` — offline: логика поиска, сортировки и формовки вывода на
фейковом `Loader` (`SupportRepoTest`), без файловой системы. Живой путь — прогоном бинаря (JSON-RPC
`initialize` → `tools/call get_ticket {"id":"TICKET-4412"}`).

## Зависимости

`mcp-kotlin-sdk` + `serialization-json` (схемы инструментов и парсинг фикстуры) + `coroutines`.
Ktor НЕ нужен — stdio на `kotlinx-io`.

## Грабли

- **Read-only по замыслу** — сервер не пишет, обновление тикетов не поддерживается. Для демо этого
  достаточно.
- **Путь к данным — первый arg**; забыли → сервер смотрит на cwd (директорию клиента), а не на
  папку с `users.json`/`tickets.json` — и первый же `get_ticket` вернёт исключение при чтении.
- Файлы читаются на каждый вызов — это не проблема на фикстуре в десятки килобайт, но не тащите
  туда большие каталоги без кэша.
- `kotlin-logging` может напечатать строку инициализации **в stdout** до JSON-RPC — наш клиент это
  терпит, строгий парсер stdout может споткнуться (общая черта всех playground-серверов).
- `addTool`-handler — extension-лямбда `ClientConnection.(CallToolRequest)`; `createSession` +
  `onClose`-latch держит сервер живым; диагностика строго в stderr (stdout — только JSON-RPC).
