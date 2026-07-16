# :playground:git-mcp — git MCP-сервер (read-only)

Standalone **MCP-сервер** (Model Context Protocol) по stdio: даёт ассистенту read-only-доступ к
живому VCS-состоянию проекта. На **Kotlin MCP SDK** (`io.modelcontextprotocol:kotlin-sdk`). Пакет
`ru.den.writes.code.agenticHub.mcps.git`, gradle-модуль `:playground:git-mcp`. Не зависит на
доменные/LLM-модули.

Спавнится MCP-клиентом подпроцессом. **Корень репозитория — первый аргумент** (`main(args)`); при
отсутствии — рабочая директория процесса. `main()` крутит сервер до отключения клиента (stdin
закрылся). stdout — JSON-RPC; диагностика — в stderr.

## Инструменты (все read-only)
- **`current_branch`** `{}` — текущая ветка (`git rev-parse --abbrev-ref HEAD`); detached-HEAD →
  явная пометка.
- **`list_files`** `{ "subdir"?: string }` — отслеживаемые git-ом файлы (`git ls-files`), опц. в
  пределах подпапки.
- **`diff`** `{ "staged"?: boolean }` — diff рабочего дерева (или staged/index при `staged=true`);
  пусто → внятная пометка.

## Запуск
```bash
./gradlew :playground:git-mcp:installDist
BIN=./playground/git-mcp/build/install/git-mcp/bin/git-mcp
$BIN /путь/к/репозиторию      # сервер на stdio (спавнится MCP-клиентом)
```
Подключение к ассистенту (cliJvmApp): путь к репо — вторым словом в команде сервера, т.к.
`-mcpServer` бьётся по whitespace:
```bash
cliJvmApp -mcpServer "$BIN /путь/к/репозиторию"
```
Инструменты обнаруживаются клиентом автоматически (`listTools`) — код клиента не меняется.

## Раскладка
Всё под `src/main/kotlin/ru/den/writes/code/agenticHub/mcps/git/`: `main.kt` (репо-путь из args →
`runGitServer`), `GitServer.kt` (регистрация инструментов + transport), `GitTools.kt`
(`CommandRunner`-порт, `GitRepo` — построение git-аргументов и формовка вывода,
`ProcessCommandRunner` — реальный `git` через `ProcessBuilder`).

## Тесты
`./gradlew :playground:git-mcp:test` — offline: построение аргументов и парсинг вывода на фейковом
`CommandRunner` (`GitToolsTest`), без реального git. Живой путь — прогоном бинаря (JSON-RPC
`initialize` → `tools/call current_branch`).

## Зависимости
`mcp-kotlin-sdk` + `serialization-json` (схемы инструментов) + `coroutines`. Ktor НЕ нужен — stdio
на `kotlinx-io`; git запускается подпроцессом, а не по HTTP.

## Грабли
- **Read-only по замыслу** — только `rev-parse`/`ls-files`/`diff`, репозиторий не мутируется.
- **Репо-путь — первый arg**; забыли → сервер смотрит на cwd (директорию клиента), не на проект.
- Ненулевой exit `git` → вывод `git error: <stderr>` как результат инструмента (не бросок через
  протокол).
- `addTool`-handler — extension-лямбда `ClientConnection.(CallToolRequest)`; `createSession` +
  `onClose`-latch держит сервер живым; диагностика строго в stderr (stdout — только JSON-RPC).
