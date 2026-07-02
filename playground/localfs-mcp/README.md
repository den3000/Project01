# :playground:localfs-mcp — MCP-сервер локальной ФС

Standalone **MCP-сервер** (Model Context Protocol) по stdio: копит документ в памяти и пишет на
диск. На **Kotlin MCP SDK** (`io.modelcontextprotocol:kotlin-sdk`). Пакет
`ru.den.writes.code.agenticHub.mcps.localfs`, gradle-модуль `:playground:localfs-mcp`. Не зависит на
доменные/LLM-модули.

Спавнится MCP-клиентом подпроцессом. Флагов/режимов нет: `main()` крутит сервер до отключения
клиента. stdout — JSON-RPC; диагностика — в stderr.

## Инструменты
- **`append_to_document`** `{ "text": string }` — дописать строку в in-memory документ (на сессию).
  Generic: любой текст, напр. погода-строка от инструмента другого сервера.
- **`save_document`** `{ "filename"?: string }` — записать накопленное в
  `~/.project01-localfs/documents/<имя>` (дефолт `document.md`), вернуть путь. Имя срезается до
  base name → запись вне каталога documents невозможна (no traversal).

Оба инструмента делят один `DocumentStore` (in-memory буфер под `Mutex`): клиент копит документ
серией `append_to_document`, затем сбрасывает `save_document`.

## Кросс-серверный пайплайн
Файловая половина MCP-оркестрации. LLM, ведущий оба сервера, строит цепочку:
```
current_weather [openmeteo-mcp] → append_to_document [localfs-mcp] → save_document [localfs-mcp]
```
Погода-строка от первого сервера уходит дословно в аргумент `text` — данные текут **между**
серверами. Прогон с двумя `-mcpServer` — в корневом README.

## Запуск
```bash
./gradlew :playground:localfs-mcp:installDist
BIN=./playground/localfs-mcp/build/install/localfs-mcp/bin/localfs-mcp
$BIN          # сервер на stdio (спавнится MCP-клиентом)
```

## Раскладка
Всё под `src/main/kotlin/ru/den/writes/code/agenticHub/mcps/localfs/`: `main.kt`
(`runFileSystemServer()`), `FileSystemServer.kt` (регистрация инструментов + transport),
`Document.kt` (`DocumentStore` + чистые `renderDocument`/`documentFileFor`/`saveDocument`).

## Тесты
`./gradlew :playground:localfs-mcp:test` — offline: рендер документа, резолв имени (guard от
traversal), запись файла. `DocumentTest`; живой путь — прогоном бинаря.

## Зависимости
`mcp-kotlin-sdk` + `serialization-json` (схемы инструментов) + `coroutines`. Ktor НЕ нужен — stdio
на `kotlinx-io`.

## Грабли
- **`addTool`-handler — extension-лямбда `ClientConnection.(CallToolRequest)`** (ОДИН параметр +
  receiver); 2-арг `StdioServerTransport` deprecated → trailing-лямбда; `createSession` + `onClose`-latch держит сервер живым.
