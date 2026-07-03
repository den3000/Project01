# :agenticHubClient:features:lifecycle:start — первоначальный запуск

KMP-модуль (common; таргеты jvm/android/ios): диспетчер `StartCommand` — исполняет admin-операции и
отдаёт session-команду наверх для запуска сессии. Тонкий CLI-facing слой поверх БД. Платформенного
кода нет: домашняя папка (`MEMORY_ROOT`) через `platform:fileSystem.homeDirectory()`, stderr — через
`platform:logging.logErr()`. `StartExecutorTest` в `commonTest` под `@IgnoreIos` (поднимает `TestDb`).

## Публичный API
- `StartExecutor(db, fs).execute(command): StartCommand.SessionInitialState?` — admin (list/clean/inflate/
  memory) исполняет через `AdminOps` и печатает `AdminNotice` по stream-тегу → возвращает `null`;
  session-команду (`RunChat`/`RunOneShot`) возвращает наверх (`StartExecutor.kt`). `fs: LocalFileSystem`
  (для memory-операций через `AdminOps`) — обязателен, из графа.
- `AdminOps(db, fs)` (+ `AdminNotice`/`OutputStream`/`formatSessionLine`) — логика admin-операций
  против БД, возвращает `AdminNotice` (не печатает сам) (`AdminOps.kt`).
- public `MEMORY_ROOT` — корень файловой памяти (`~/.project01-cli/memory`), используется и
  `CliRepl` в апп-модуле.
- `di/`: `startModule` — `single { StartExecutor(db = get(), fs = get()) }`. Общая дока — [DI.md](../../../DI.md).

## Зависимости
- `api(lifecycle:command)` + `api(platform:database)`, `implementation(features:memory)`,
  `implementation(platform:fileSystem)` (прямая — `fs` в `AdminOps`) + `implementation(koin.core)` (для di).
  Потребитель — `apps:cliJvmApp` (`main.kt`, через граф).

## Тесты
`./gradlew :agenticHubClient:features:lifecycle:start:test` — `StartExecutorTest` (admin → null;
session → возвращается), `SessionListFormatTest` (`TestDb` из platform:database).
