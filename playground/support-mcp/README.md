# :playground:support-mcp — support MCP-сервер (read-write, поверх JSON)

Standalone **MCP-сервер** по stdio: система записи тикетов и справочник пользователей для
чата-ассистента поддержки CTT. На **Kotlin MCP SDK** (`io.modelcontextprotocol:kotlin-sdk`). Пакет
`ru.den.writes.code.agenticHub.mcps.support`, gradle-модуль `:playground:support-mcp`. Не зависит на
доменные/LLM-модули.

Спавнится MCP-клиентом подпроцессом. Аргументы: **первый — путь к папке** с `users.json` и
`tickets.json` (по умолчанию — рабочая директория процесса); **`--dev`** (опц., любая позиция) —
запуск разработчика: дополнительно открывает мутатор `set_ticket_status`. `main()` крутит сервер до
отключения клиента. stdout — JSON-RPC; диагностика — в stderr.

## Инструменты

Read (всегда):

- **`find_user`** `{ "name": string }` — пользователи по имени (регистронезависимо, подстрока). Пусто
  = собеседник не зарегистрирован → ассистент вежливо отказывает. Основа «гость vs зарегистрированный».
- **`get_user`** `{ "id": string }` — полный клиент (name, email, tariff, product, since).
- **`list_user_tickets`** `{ "customerId": string }` — тикеты клиента (для повторного визита).
- **`search_tickets`** `{ "query": string }` — по ВСЕМ тикетам вкл. `resolved` (переиспользование
  готовых решений между пользователями).
- **`get_ticket`** `{ "id": string }` — полный тикет: status, priority, resolution, comments.
- **`list_tickets`** `{}` — все тикеты, свежеобновлённые сверху.

Write:

- **`create_ticket`** `{ "customerId", "subject", "description" }` — эскалация: новый тикет
  `status=new` (id `TICKET-<max+1>`), append+persist, возвращает id. Доступен всегда; для
  незарегистрированного `customerId` — отказ.
- **`set_ticket_status`** `{ "ticketId", "status", "resolution" }` — **только при `--dev`**: статус
  (`new`/`in_progress`/`resolved`/`wontfix`) + resolution + комментарий. Неизвестный id/статус →
  пометка.

Данные читаются с диска **при каждом вызове** (кэша нет) — правка `tickets.json`/`users.json` (или
запись от `create_ticket`/`set_ticket_status`) видна следующему вызову без рестарта.

## Гейт разработчика — запуск, а не токен

Право менять статус тикета даёт **флаг запуска сервера `--dev`**, а не строка в чате: без него
`set_ticket_status` вообще не регистрируется (`tools/list` его не покажет). «Кто разработчик» решает
конфиг запуска — в реальном деплое это аутентификация фронтенда. Так секрет не течёт через модель.

## Запуск

```bash
./gradlew :playground:support-mcp:installDist
BIN=./playground/support-mcp/build/install/support-mcp/bin/support-mcp
DATA=./demo/ctt-support
$BIN "$DATA"            # пользовательский сервер (read + create_ticket)
$BIN "$DATA" --dev      # серверный режим разработчика (+ set_ticket_status)
```

Готовые ролевые запуски (профили, RAG, стадии, судья) — обёртки
[`demo/ctt-support/run-support.sh` / `run-dev.sh`](../../demo/ctt-support/). Инструменты
обнаруживаются клиентом автоматически (`listTools`).

## Формат фикстуры

Лишние поля игнорируются (`ignoreUnknownKeys`), `status`/`resolution` необязательны при чтении
(`status` по умолчанию `new`):

```json
// users.json
[{"id":"USER-101","name":"…","email":"…","tariff":"business","product":"CTT","since":"2024-06-15"}]

// tickets.json
[{"id":"TICKET-4415","subject":"…","description":"…","status":"resolved","priority":"normal",
  "createdAt":"…","updatedAt":"…","customerId":"USER-103",
  "resolution":"…","comments":[{"author":"developer","at":"…","text":"status → resolved"}]}]
```

Смысловой словарь статусов — [demo/ctt-support/README.md](../../demo/ctt-support/README.md).

## Раскладка

`src/main/kotlin/…/mcps/support/`: `main.kt` (dataRoot + `--dev` из argv → `runSupportServer`),
`SupportServer.kt` (регистрация инструментов + transport), `SupportRepo.kt` (`Store`-порт,
`SupportRepo` — поиск/создание/смена статуса и форматирование, `FileStore` — реальный File IO,
таймстемп инъектируется).

## Тесты

`./gradlew :playground:support-mcp:test` — offline на in-memory `Store`: `SupportRepoTest` (поиск,
роли, resolution), `SupportRepoWriteTest` (createTicket append + id, setTicketStatus happy/отказы).
Живой путь — прогоном бинаря (`initialize` → `tools/call`).

## Зависимости

`mcp-kotlin-sdk` + `serialization-json` (схемы + фикстура) + `coroutines`. Ktor НЕ нужен — stdio на
`kotlinx-io`.

## Грабли

- **Путь к данным — первый позиционный arg**; забыли → сервер смотрит на cwd клиента, первый же
  `find_user`/`get_ticket` бросит исключение при чтении.
- **`set_ticket_status` есть только с `--dev`** — в пользовательском запуске его нет by design.
- Файлы читаются на каждый вызов — норм на фикстуре в десятки КБ, не тащите туда большие каталоги.
- `kotlin-logging` может печатать строку инициализации **в stdout** до JSON-RPC — наш клиент терпит,
  строгий парсер споткнётся (общая черта playground-серверов).
- `addTool`-handler — extension-лямбда `ClientConnection.(CallToolRequest)`; `createSession` +
  `onClose`-latch держит сервер живым; диагностика строго в stderr.
