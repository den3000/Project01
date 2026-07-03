# :agenticHubClient:testing — общие тест-хелперы

KMP-модуль с shared-хелперами для тестов. Потребляется **только** через
`testImplementation(projects.agenticHubClient.testing)` — в прод-артефакты не попадает. Kotlin
Multiplatform не поддерживает `java-test-fixtures`, поэтому выделенный модуль — идиоматичный способ
шарить фейки без дублей по модулям.

## Публичный API
- `FakeLlmApi` (commonMain) — детерминированный стаб `LlmApi`: очередь ответов (`queue`/`queueText`),
  инспекция вызовов (`calls`). Пустая очередь → синтетический error-результат.
- `TestDb` (jvmMain) — одноразовая Room-БД на temp-файле (bundled SQLite driver), `AutoCloseable`
  (чистит `.db`/`-wal`/`-shm` в `close()`; использовать в `.use { … }`).
- `testLocalFileSystem()` (commonMain, `TestFileSystem.kt`) — `LocalFileSystem`, резолвится из
  `fileSystemModule` через изолированный `koinApplication` (koin-core, без koin-test). Для тестов,
  строящих `FileMemoryStore(root, fs = …)`. Общая дока по DI — [DI.md](../DI.md).

> Для **композиции Koin-графа** (интеграционные тесты) фейки живут не здесь, а рядом со своими
> прод-модулями как `*TestModule` (`fileSystemTestModule`/`llmTestModule` + holder `FakeLlmScript`) —
> см. [DI.md](../DI.md). `FakeLlmApi` здесь остаётся для unit-тестов, конструирующих фейк напрямую.

## Зависимости
- commonMain `api(features:llm)` (FakeLlmApi) + `api(platform:fileSystem)` + `koin.core`
  (testLocalFileSystem); jvmMain `api(platform:database)` + sqlite-bundled (TestDb). Потребители
  (testImplementation): features:agent, features:memory, platform:database, lifecycle:session,
  lifecycle:start, apps:cliJvmApp.
