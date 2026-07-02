# :agenticHubClient:features:lifecycle:start — первоначальный запуск

JVM-модуль: диспетчер `StartCommand` — исполняет admin-операции и отдаёт session-команду наверх для
запуска сессии. Тонкий CLI-facing слой поверх БД.

## Публичный API
- `StartExecutor(db).execute(command): StartCommand.SessionInitialState?` — admin (list/clean/inflate/
  memory) исполняет через `AdminOps` и печатает `AdminNotice` по stream-тегу → возвращает `null`;
  session-команду (`RunChat`/`RunOneShot`) возвращает наверх (`StartExecutor.kt`).
- `AdminOps` (+ `AdminNotice`/`OutputStream`/`formatSessionLine`) — логика admin-операций против БД,
  возвращает `AdminNotice` (не печатает сам) (`AdminOps.kt`).
- public `MEMORY_ROOT` — корень файловой памяти (`~/.project01-cli/memory`), используется и
  `SessionRunner` в апп-модуле.

## Зависимости
- `api(lifecycle:command)` + `api(platform:database)`, `implementation(features:memory)`.
  Потребитель — `apps:cliJvmApp` (`main.kt`).

## Тесты
`apps:cliJvmApp:test` — `StartExecutorTest` (admin → null; session → возвращается), `SessionListFormatTest`.
