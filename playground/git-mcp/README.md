# :playground:git-mcp — git MCP-сервер (read-only)

Standalone **MCP-сервер** (Model Context Protocol) по stdio: даёт ассистенту read-only-доступ к
живому VCS-состоянию проекта. На **Kotlin MCP SDK** (`io.modelcontextprotocol:kotlin-sdk`). Пакет
`ru.den.writes.code.agenticHub.mcps.git`, gradle-модуль `:playground:git-mcp`. Не зависит на
доменные/LLM-модули.

Спавнится MCP-клиентом подпроцессом. Аргументы: **первый — корень репозитория** (по умолчанию —
рабочая директория процесса), **второй (опц.) — база диапазона** (см. ниже). `main()` крутит сервер
до отключения клиента (stdin закрылся). stdout — JSON-RPC; диагностика — в stderr.

## Инструменты (все read-only)
- **`current_branch`** `{}` — текущая ветка (`git rev-parse --abbrev-ref HEAD`); detached-HEAD →
  явная пометка.
- **`list_files`** `{ "subdir"?: string }` — отслеживаемые git-ом файлы (`git ls-files`), опц. в
  пределах подпапки. Отдаёт **весь** список — для «что изменилось» берите `changed_files`.
- **`changed_files`** `{ "base"?, "head"? }` — изменённые файлы диапазона
  (`git diff --name-only <base>...<head>`).
- **`diff`** `{ "base"?, "head"?, "staged"?: boolean }` — diff диапазона `<base>...<head>`; без базы —
  рабочее дерево (или staged при `staged=true`). Пусто → внятная пометка.

### База диапазона
`<base>...<head>` — **три точки**: изменения ветки от точки ветвления, без того, что тем временем
попало в базу. Это и есть смысл «отревьюить PR».

База берётся из аргумента вызова, иначе из **второго аргумента сервера**. Второй аргумент — ради
надёжности: пайплайн задаёт базу один раз, и модель зовёт `diff({})` / `changed_files({})` **без
аргументов**, не имея шанса ошибиться с ref. Без базы вообще инструменты смотрят на рабочее дерево —
в CI оно чистое, поэтому для ревью база обязательна.

## Запуск
```bash
./gradlew :playground:git-mcp:installDist
BIN=./playground/git-mcp/build/install/git-mcp/bin/git-mcp
$BIN /путь/к/репозиторию                    # diff = рабочее дерево
$BIN /путь/к/репозиторию <base-sha>         # diff = <base-sha>...HEAD
```
Подключение к ассистенту (cliJvmApp): аргументы идут словами в команде сервера, т.к. `-mcpServer`
бьётся по whitespace:
```bash
cliJvmApp -mcpServer "$BIN /путь/к/репозиторию <base-sha>"
```
Инструменты обнаруживаются клиентом автоматически (`listTools`) — код клиента не меняется.

## Раскладка
Всё под `src/main/kotlin/ru/den/writes/code/agenticHub/mcps/git/`: `main.kt` (репо-путь и база из
args → `runGitServer`), `GitServer.kt` (регистрация инструментов + transport), `GitTools.kt`
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
- **Без базы `diff` смотрит на рабочее дерево** — в CI после checkout оно чистое, и ответ будет
  «(no unstaged changes)». Для ревью PR база обязательна (вторым arg).
- `staged` **игнорируется**, когда база в игре: диапазон и индекс — разные вопросы, диапазон важнее.
- `kotlin-logging` печатает строку инициализации **в stdout** до JSON-RPC — наш клиент это терпит,
  строгий парсер stdout может споткнуться (общая черта всех playground-серверов).
- Ненулевой exit `git` → вывод `git error: <stderr>` как результат инструмента (не бросок через
  протокол).
- `addTool`-handler — extension-лямбда `ClientConnection.(CallToolRequest)`; `createSession` +
  `onClose`-latch держит сервер живым; диагностика строго в stderr (stdout — только JSON-RPC).
